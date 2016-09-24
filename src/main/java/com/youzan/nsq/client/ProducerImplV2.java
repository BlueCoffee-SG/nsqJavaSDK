package com.youzan.nsq.client;

import com.youzan.nsq.client.core.LookupAddressUpdate;
import com.youzan.nsq.client.core.NSQConnection;
import com.youzan.nsq.client.core.NSQSimpleClient;
import com.youzan.nsq.client.core.command.*;
import com.youzan.nsq.client.core.pool.producer.KeyedPooledConnectionFactory;
import com.youzan.nsq.client.entity.*;
import com.youzan.nsq.client.exception.*;
import com.youzan.nsq.client.network.frame.ErrorFrame;
import com.youzan.nsq.client.network.frame.NSQFrame;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    private static final int MAX_PUBLISH_RETRY = 6;

    private static final Logger logger = LoggerFactory.getLogger(ProducerImplV2.class);
    private volatile boolean started = false;
    private volatile int offset = 0;
    private final GenericKeyedObjectPoolConfig poolConfig;
    private final KeyedPooledConnectionFactory factory;
    private GenericKeyedObjectPool<Address, NSQConnection> bigPool = null;
    private final ScheduledExecutorService scheduler = Executors
            .newSingleThreadScheduledExecutor(new NamedThreadFactory(this.getClass().getName(), Thread.NORM_PRIORITY));
    private final ConcurrentHashMap<Topic, Long> topic_2_lastActiveTime = new ConcurrentHashMap<>();

    private final AtomicInteger success = new AtomicInteger(0);
    private final AtomicInteger total = new AtomicInteger(0);

    private final NSQConfig config;
    private final NSQSimpleClient simpleClient;
    //trace works on update nsq command to insert trace ID into command,
    //producer delegates to trace activities to trace
    private NSQTrace trace = new NSQTrace();
    /**
     * @param config NSQConfig
     */
    public ProducerImplV2(NSQConfig config) {
        this.config = config;
        this.simpleClient = new NSQSimpleClient(config.getLookupAddresses(new Long(0l)), new LookupAddressUpdate(config));

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
            this.poolConfig.setTestOnBorrow(false);
            this.poolConfig.setTestOnReturn(false);
            this.poolConfig.setTestWhileIdle(true);
            this.poolConfig.setJmxEnabled(true);
            this.poolConfig.setMinEvictableIdleTimeMillis(5 * 60 * 1000);
            this.poolConfig.setSoftMinEvictableIdleTimeMillis(5 * 60 * 1000);
            //1 * 60 * 1000
            this.poolConfig.setTimeBetweenEvictionRunsMillis(60 * 1000);
            this.poolConfig.setMinIdlePerKey(1);
            this.poolConfig.setMaxIdlePerKey(this.config.getThreadPoolSize4IO() + 1);
            this.poolConfig.setMaxTotalPerKey(this.config.getThreadPoolSize4IO() + 1);
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
                    final Set<Topic> expiredTopics = new HashSet<>();
                    for (Map.Entry<Topic, Long> pair : topic_2_lastActiveTime.entrySet()) {
                        if (pair.getValue() < allow) {
                            expiredTopics.add(pair.getKey());
                        }
                    }
                    for (Topic topic : expiredTopics) {
                        topic_2_lastActiveTime.remove(topic);
                        try {
                            simpleClient.removeTopic(topic);
                        } catch (Exception e) {
                            logger.error("Exception", e);
                        }
                    }
                    expiredTopics.clear();
                    logger.info("Publish. Total: {} , Success: {} . Two values do not use a lock action.", total.get(), success.get());
                }
            }, 30, 30, TimeUnit.MINUTES);
        }
        logger.info("The producer has been started.");
    }

    /**
     * Get a connection foreach every broker in one loop till get one available because I don't believe
     * that every broker is down or every pool is busy.
     *
     * @param topic a topic name
     * @return a validated {@link NSQConnection}
     * @throws NSQException that is having done a negotiation
     */
    private NSQConnection getNSQConnection(Topic topic) throws NSQException {
        final Long now = System.currentTimeMillis();
        topic_2_lastActiveTime.put(topic, now);
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
//            logger.debug("Load-Balancing algorithm is Round-Robin! DataNode Size: {} , Index: {} , Got {}", size, effectedIndex, address);
            try {
//                logger.debug("Begin to borrowObject from the address: {}", address);
                return bigPool.borrowObject(address);
            } catch (Exception e) {
                logger.error("DataNode Size: {} , CurrentRetries: {} , Address: {} , Exception:", size, c, address, e);
            }
        }
        // no available {@link NSQConnection}
        return null;
    }

    public void setTraceID(long traceID){
        this.trace.setTraceId(traceID);
    }

    public void publish(String message, String topic) throws NSQException {
        publish(message.getBytes(IOUtil.DEFAULT_CHARSET), topic);
    }

    @Override
    /**
     * publish message to topic, in ALL partitions pass in topic has,
     * function publishes message to topic, in specified partition.
     *
     * @param message message data in byte data array.
     * @param topic topic message publishes to.
     * @throws NSQException
     */
    public void publish(byte[] message, String topic) throws NSQException {
        publish(message, new Topic(topic));
    }

    @Override
    /**
     * publish message to topic, if topic partition id is specified,
     * function publishes message to topic, in specified partition.
     *
     * @param message message data in byte data array.
     * @param topic topic message publishes to.
     * @throws NSQException
     */
    public void publish(byte[] message, Topic topic) throws NSQException {
        if (message == null || message.length <= 0) {
            throw new IllegalArgumentException("Your input message is blank! Please check it!");
        }
        if (null == topic || null == topic.getTopicText() || topic.getTopicText().isEmpty()) {
            throw new IllegalArgumentException("Your input topic name is blank!");
        }
        if (!started) {
            throw new IllegalStateException("Producer must be started before producing messages!");
        }
        total.incrementAndGet();
        final Pub pub = createPubCmd(topic, message);
        sendPUB(pub, topic, message);
    }

    private void sendPUB(final Pub pub, final Topic topic, final byte[] message) throws NSQException {
        int c = 0; // be continuous
        while (c++ < MAX_PUBLISH_RETRY) {
            if (c > 1) {
                logger.debug("Sleep. CurrentRetries: {}", c);
                sleep((1 << (c - 1)) * 1000);
            }
            final NSQConnection conn = getNSQConnection(topic);
            if (conn == null) {
                continue;
            }
//            logger.debug("Having acquired a {} NSQConnection! CurrentRetries: {}", conn.getAddress(), c);
            try {
                final NSQFrame frame = conn.commandAndGetResponse(pub);
                handleResponse(frame, conn);
                if(frame.getType() == NSQFrame.FrameType.RESPONSE_FRAME && pub instanceof HasTraceID){
                    //gather trace info
                    this.trace.handleFrame(pub, frame);
                }
                success.incrementAndGet();
                return;
            } catch (Exception e) {
                IOUtil.closeQuietly(conn);
                logger.error("MaxRetries: {} , CurrentRetries: {} , Address: {} , Topic: {}, RawMessage: {} , Exception:", MAX_PUBLISH_RETRY, c,
                        conn.getAddress(), topic, message, e);
                if (c >= MAX_PUBLISH_RETRY) {
                    throw new NSQDataNodesDownException(e);
                }
            } finally {
                bigPool.returnObject(conn.getAddress(), conn);
            }
        }
    }

    /**
     * function to create publish command based on traceability
     * @return Pub command
     */
    private Pub createPubCmd(final Topic topic, final byte[] messages){
        if(this.config.isOrdered()){
            return new PubOrdered(topic, messages);
        }
        else if(this.trace.isTraceOn(topic)) {
            Pub cmd =  new PubTrace(topic, messages);
            this.trace.traceDebug("{} created.", cmd.toString());
            this.trace.insertTraceID(cmd);
            return cmd;
        }else{
            return new Pub(topic, messages);
        }
    }

    private void handleResponse(NSQFrame frame, NSQConnection conn) throws NSQException {
        if (frame == null) {
            logger.warn("SDK bug: the frame is null.");
            return;
        }
        switch (frame.getType()) {
            case RESPONSE_FRAME: {
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
    }


    @Override
    public void publishMulti(List<byte[]> messages, String topic) throws NSQException {
    }

    @Override
    public boolean validateHeartbeat(NSQConnection conn) {
        return simpleClient.validateHeartbeat(conn);
    }

    @Override
    public Set<NSQConnection> clearDataNode(Address address) {
        return null;
    }

    @Override
    public void close() {
        IOUtil.closeQuietly(simpleClient);
        if (factory != null) {
            factory.close();
        }
        if (bigPool != null) {
            bigPool.close();
        }
        scheduler.shutdownNow();
        logger.info("The producer has been closed.");
    }

    public void close(NSQConnection conn) {
        conn.close();
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
