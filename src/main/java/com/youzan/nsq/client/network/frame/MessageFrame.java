package com.youzan.nsq.client.network.frame;

import java.util.Arrays;

public class MessageFrame extends NSQFrame {
    /*-
     * =========================================================================
     *                       NSQ the message format
     * =========================================================================
     */
    /**
     * 8-byte : nanosecond timestamp (int64)
     */
    private final byte[] timestamp = new byte[8];
    /**
     * 2-byte : (uint16)
     */
    private final byte[] attempts = new byte[2];
    /**
     * 16-byte : (hex string encoded in ASCII)
     */
    private final byte[] messageID = new byte[16];
    /**
     * N-byte : (binary)
     */
    private byte[] messageBody;
    /*-
     * =========================================================================
     *                       NSQ the message format -- Ending
     * =========================================================================
     */

    /**
     * @return the timestamp
     */
    public byte[] getTimestamp() {
        return timestamp;
    }

    /**
     * @return the attempts
     */
    public byte[] getAttempts() {
        return attempts;
    }

    /**
     * @return the messageID
     */
    public byte[] getMessageID() {
        return messageID;
    }

    /**
     * @return the messageBody
     */
    public byte[] getMessageBody() {
        return messageBody;
    }

    /**
     * @param messageBody the messageBody to set
     */
    private void setMessageBody(byte[] messageBody) {
        this.messageBody = messageBody;
    }

    @Override
    public void setData(byte[] bytes) {
        final int messageBodySize = bytes.length - (8 + 2 + 16);
        messageBody = new byte[messageBodySize];
        System.arraycopy(bytes, 0, timestamp, 0, 8);
        System.arraycopy(bytes, 8, attempts, 0, 2);
        System.arraycopy(bytes, 10, messageID, 0, 16);
        System.arraycopy(bytes, 26, messageBody, 0, messageBodySize);
    }

    @Override
    public FrameType getType() {
        return FrameType.MESSAGE_FRAME;
    }

    @Override
    public String getMessage() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
        return "MessageFrame{" +
                " messageID=" + Arrays.toString(messageID) +
                ", attempts=" + Arrays.toString(attempts) +
                ", timestamp=" + Arrays.toString(timestamp) +
                '}';
    }
}
