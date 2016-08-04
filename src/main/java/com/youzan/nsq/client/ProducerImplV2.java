package com.youzan.nsq.client;

import com.youzan.nsq.client.core.KeyedPooledConnectionFactory;
import com.youzan.nsq.client.core.NSQConnection;
import com.youzan.nsq.client.core.NSQSimpleClient;
import com.youzan.nsq.client.core.command.Pub;
import com.youzan.nsq.client.entity.Address;
import com.youzan.nsq.client.entity.NSQConfig;
import com.youzan.nsq.client.exception.*;
import com.youzan.nsq.client.network.frame.ErrorFrame;
import com.youzan.nsq.client.network.frame.NSQFrame;
import com.youzan.nsq.client.network.frame.ResponseFrame;
import com.youzan.util.ConcurrentSortedSet;
import com.youzan.util.IOUtil;
import com.youzan.util.NamedThreadFactory;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <pre>
 * Use {@link NSQConfig} to set the lookup cluster.
 * It uses one connection pool(client connects to one broker) underlying TCP and uses
 * {@link GenericKeyedObjectPool} which is composed of many sub-pools.
 * </pre>
 *
 * @author <a href="mailto:my_email@email.exmaple.com">zhaoxi (linzuxiong)</a>
 */
public class ProducerImplV2 implements Producer {

    private static final Logger logger = LoggerFactory.getLogger(ProducerImplV2.class);
    private volatile boolean started = false;
    private volatile int offset = 0;

    private final GenericKeyedObjectPoolConfig poolConfig;
    private final KeyedPooledConnectionFactory factory;
    private GenericKeyedObjectPool<Address, NSQConnection> bigPool = null;
    private final ScheduledExecutorService scheduler = Executors
            .newSingleThreadScheduledExecutor(new NamedThreadFactory(this.getClass().getName(), Thread.NORM_PRIORITY));
    private final ConcurrentMap<String, Long> topic_2_lastActiveTime = new ConcurrentHashMap<>();
    private final ConcurrentMap<Address, Long> address_2_lastActiveTime = new ConcurrentHashMap<>();

    private final AtomicInteger success = new AtomicInteger(0);
    private final AtomicInteger total = new AtomicInteger(0);

    private final NSQConfig config;
    private final NSQSimpleClient simpleClient;

    /**
     * @param config NSQConfig
     */
    public ProducerImplV2(NSQConfig config) {
        this.config = config;
        this.simpleClient = new NSQSimpleClient(config.getLookupAddresses());

        this.poolConfig = new GenericKeyedObjectPoolConfig();
        this.factory = new KeyedPooledConnectionFactory(this.config, this);
    }

    @Override
    public void start() throws NSQException {
        if (!this.started) {
            this.started = true;
            // setting all of the configs
            this.offset = _r.nextInt(100);
            this.poolConfig.setLifo(true);
            this.poolConfig.setFairness(false);
            this.poolConfig.setTestOnBorrow(true);
            this.poolConfig.setTestOnReturn(false);
            this.poolConfig.setTestWhileIdle(true);
            this.poolConfig.setJmxEnabled(false);
            this.poolConfig.setMinEvictableIdleTimeMillis(60 * 1000);
            this.poolConfig.setSoftMinEvictableIdleTimeMillis(60 * 1000);
            this.poolConfig.setTimeBetweenEvictionRunsMillis(15 * 1000);
            this.poolConfig.setMinIdlePerKey(1);
            this.poolConfig.setMaxIdlePerKey(this.config.getThreadPoolSize4IO());
            this.poolConfig.setMaxTotalPerKey(this.config.getThreadPoolSize4IO());
            // acquire connection waiting time
            this.poolConfig.setMaxWaitMillis(this.config.getQueryTimeoutInMillisecond());
            this.poolConfig.setBlockWhenExhausted(false);
            // new instance without performing to connect
            this.bigPool = new GenericKeyedObjectPool<>(this.factory, this.poolConfig);
            //
            this.simpleClient.start();
            scheduler.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    // We make a decision that the resources life time should be less than 2 hours
                    // Normal max lifetime is 1 hour
                    // Extreme max lifetime is 1.5 hours
                    final long allow = System.currentTimeMillis() - 3600 * 1000L;
                    final Set<String> expiredTopics = new HashSet<>();
                    final Set<Address> expiredAddresses = new HashSet<>();
                    for (Map.Entry<String, Long> pair : topic_2_lastActiveTime.entrySet()) {
                        if (pair.getValue().longValue() < allow) {
                            expiredTopics.add(pair.getKey());
                        }
                    }
                    for (Map.Entry<Address, Long> pair : address_2_lastActiveTime.entrySet()) {
                        if (pair.getValue().longValue() < allow) {
                            expiredAddresses.add(pair.getKey());
                        }
                    }
                    for (Address address : expiredAddresses) {
                        address_2_lastActiveTime.remove(address);
                        try {
                            bigPool.clear(address);
                            factory.clear(address);
                        } catch (Exception e) {
                            logger.error("Exception", e);
                        }
                    }
                    for (String topic : expiredTopics) {
                        topic_2_lastActiveTime.remove(topic);
                        try {
                            simpleClient.removeTopic(topic);
                        } catch (Exception e) {
                            logger.error("Exception", e);
                        }
                    }
                    expiredTopics.clear();
                    expiredAddresses.clear();
                }
            }, 30, 30, TimeUnit.MINUTES);
        }
        logger.info("Producer is started.");
    }

    /**
     * Get a connection foreach every broker in one loop till get one available because I don't believe
     * that every broker is down or every pool is busy.
     *
     * @param topic a topic name
     * @return a validated {@link NSQConnection}
     * @throws NSQException that is having done a negotiation
     */
    private NSQConnection getNSQConnection(String topic) throws NSQException {
        final Long now = Long.valueOf(System.currentTimeMillis());
        topic_2_lastActiveTime.putIfAbsent(topic, now);
        final ConcurrentSortedSet<Address> dataNodes = simpleClient.getDataNodes(topic);
        if (dataNodes.isEmpty()) {
            throw new NSQNoConnectionException("You still do not producer.start() or the server is down(contact the administrator)!");
        }
        final int size = dataNodes.size();
        final Address[] addresses = dataNodes.newArray(new Address[size]);
        int c = 0, index = (this.offset++);
        while (c++ < size) {
            // current broker | next broker when have a try again
            final int effectedIndex = (index++ & Integer.MAX_VALUE) % size;
            final Address address = addresses[effectedIndex];
            address_2_lastActiveTime.putIfAbsent(address, now);
            logger.debug("Load-Balancing algorithm is Round-Robin! Size: {} , Index: {} , Got {}", size, effectedIndex,
                    address);
            try {
                logger.debug("Begin to borrowObject from the address: {}", address);
                return bigPool.borrowObject(address);
            } catch (Exception e) {
                logger.error("CurrentRetries: {} , Address: {} , Exception:", c, address, e);
            }
        }
        // no available {@link NSQConnection}
        return null;
    }

    public void publish(String message, String topic) throws NSQException {
        publish(message.getBytes(IOUtil.DEFAULT_CHARSET), topic);
    }

    @Override
    public void publish(byte[] message, String topic) throws NSQException {
        if (message == null || message.length <= 0) {
            throw new IllegalArgumentException("Your input message is blank! Please check it!");
        }
        if (null == topic || topic.isEmpty()) {
            throw new IllegalArgumentException("Your input topic name is blank!");
        }
        if (!started) {
            throw new IllegalStateException("Producer must be started before producing messages!");
        }
        total.incrementAndGet();
        final Pub pub = new Pub(topic, message);
        final int maxRetries = 6;
        int c = 0; // be continuous
        while (c++ < maxRetries) {
            if (c > 1) {
                logger.debug("Sleep. CurrentRetries: {}", c);
                sleep((1 << (c - 1)) * 1000);
            }
            final NSQConnection conn = getNSQConnection(topic);
            if (conn == null) {
                continue;
            }
            logger.debug("Having acquired a {} NSQConnection! CurrentRetries: {}", conn.getAddress(), c);
            try {
                final NSQFrame frame = conn.commandAndGetResponse(pub);
                handleResponse(frame, conn);
                success.incrementAndGet();
                return;
            } catch (Exception e) {
                IOUtil.closeQuietly(conn);
                logger.error("MaxRetries: {} , CurrentRetries: {} , Address: {} , Topic: {}, RawMessage: {} , Exception:", maxRetries,
                        conn.getAddress(), topic, message, e);
                if (c >= maxRetries) {
                    throw new NSQException(e);
                }
            } finally {
                bigPool.returnObject(conn.getAddress(), conn);
            }
        }
        throw new NSQDataNodesDownException();
    }

//        final List<List<byte[]>> batches = Lists.partition(messages, 30);
//        for (List<byte[]> batch : batches) {
//            publishSmallBatch(batch);
//        }

    private void handleResponse(NSQFrame frame, NSQConnection conn) throws NSQException {
        if (frame == null) {
            logger.warn("SDK bug: the frame is null.");
            return;
        }
        switch (frame.getType()) {
            case RESPONSE_FRAME: {
                final ResponseFrame f = (ResponseFrame) frame;
                logger.debug("Publish ok, get response is {}", f.getMessage());
                break;
            }
            case ERROR_FRAME: {
                final ErrorFrame err = (ErrorFrame) frame;
                switch (err.getError()) {
                    case E_BAD_TOPIC: {
                        throw new NSQInvalidTopicException();
                    }
                    case E_BAD_MESSAGE: {
                        throw new NSQInvalidMessageException();
                    }
                    case E_FAILED_ON_NOT_LEADER: {
                    }
                    case E_FAILED_ON_NOT_WRITABLE: {
                    }
                    case E_TOPIC_NOT_EXIST: {
                        logger.error("Address: {} , Frame: {}", conn.getAddress(), frame);
                        throw new NSQInvalidDataNodeException();
                    }
                    default: {
                        throw new NSQException("Unknown response error! The error frame is " + err);
                    }
                }
            }
            default: {
                logger.warn("When handle a response, expect FrameTypeResponse or FrameTypeError, but not.");
            }
        }
    }

    @Override
    public void incoming(NSQFrame frame, NSQConnection conn) throws NSQException {
        simpleClient.incoming(frame, conn);
    }


    @Override
    public void backoff(NSQConnection conn) {
        simpleClient.backoff(conn);
    }


    @Override
    public void publishMulti(List<byte[]> messages, String topic) throws NSQException {
    }

    @Override
    public boolean validateHeartbeat(NSQConnection conn) {
        return simpleClient.validateHeartbeat(conn);
    }

    @Override
    public void close() {
        if (factory != null) {
            factory.close();
        }
        if (bigPool != null) {
            bigPool.close();
        }
        IOUtil.closeQuietly(simpleClient);
    }

    private void sleep(final long millisecond) {
        logger.debug("Sleep {} millisecond.", millisecond);
        try {
            Thread.sleep(millisecond);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Your machine is too busy! Please check it!");
        }
    }
}
