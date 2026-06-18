package com.javaup.constant;

public final class OrderMqConstant {

    /**
     * 订单创建交换机
     */
    public static final String ORDER_CREATE_EXCHANGE = "floworder.order.exchange";

    /**
     * 订单创建队列
     */
    public static final String ORDER_CREATE_QUEUE = "floworder.order.create.queue";

    /**
     * 订单创建路由键
     */
    public static final String  ORDER_CREATE_ROUTING_KEY = "order.create";

    /**
     * 订单创建结果交换机
     */
    public static final String ORDER_RESULT_EXCHANGE = "floworder.order.result.exchange";

    /**
     * 订单创建结果队列
     */
    public static final String ORDER_RESULT_QUEUE = "floworder.order.result.queue";

    /**
     * 订单创建结果路由键
     */
    public static final String ORDER_RESULT_ROUTING_KEY = "order.create.result";

    /**
     * 订单创建事件
     */
    public static final String ORDER_CREATE_COMMAND = "ORDER_CREATE_COMMAND";

    /**
     * 订单创建成功事件
     */
    public static final String ORDER_CREATE_SUCCEEDED = "ORDER_CREATE_SUCCEEDED";

    /**
     * 订单创建失败事件
     */
    public static final String ORDER_CREATE_FAILED = "ORDER_CREATE_FAILED";

    /**
     * 订单创建消费者组
     */
    public static final String ORDER_CREATE_CONSUMER = "order-create-consumer";

    /**
     * 订单创建结果消费者组
     */
    public static final String ORDER_RESULT_CONSUMER = "order-result-consumer";

    /**
     *  资源服务
     */
    public static final String RESOURCE_SERVICE = "floworder-resource-service";

    /**
     * 订单服务
     */
    public static final String ORDER_SERVICE = "floworder-order-service";

    /**
     * 订单死信交换机
     */
    public static final String ORDER_DLX = "floworder.order.dlx";

    /**
     * 订单创建死信队列
     */
    public static final String ORDER_CREATE_DLQ = "floworder.order.create.dlq";

    /**
     * 订单创建死信路由键
     */
    public static final String ORDER_CREATE_DEAD_KEY = "order.create.dead";

    /**
     * 订单创建结果死信队列
     */
    public static final String ORDER_RESULT_DLQ = "floworder.order.result.dlq";

    /**
     * 订单创建结果死信路由键
     */
    public static final String ORDER_RESULT_DEAD_KEY = "order.result.dead";

    /**
     * 订单状态交换机
     */
    public static final String ORDER_STATE_EXCHANGE = "floworder.order.state.exchange";

    /**
     * 订单状态队列
     */
    public static final String ORDER_STATE_QUEUE = "floworder.order.state.queue";

    /**
     * 订单状态路由键
     */
    public static final String ORDER_STATE_ROUTING_KEY = "order.state.changed";

    /**
     * 订单创建
     */
    public static final String ORDER_CONFIRMED = "ORDER_CONFIRMED";

    /**
     * 订单取消
     */
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";

    /**
     * 订单超时
     */
    public static final String ORDER_TIMEOUT = "ORDER_TIMEOUT";

    /**
     * 订单状态消费者组
     */
    public static final String ORDER_STATE_CONSUMER = "order-state-consumer";

    /**
     * 订单状态死信队列
     */
    public static final String ORDER_STATE_DLQ = "floworder.order.state.dlq";

    /**
     * 订单状态死信路由键
     */
    public static final String ORDER_STATE_DEAD_KEY = "order.state.dead";

    private OrderMqConstant() {
    }
}