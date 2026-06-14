package com.javaup.constant;

public final class OrderMqConstant {

    public static final String ORDER_CREATE_EXCHANGE = "floworder.order.exchange";

    public static final String ORDER_CREATE_QUEUE = "floworder.order.create.queue";

    public static final String ORDER_CREATE_ROUTING_KEY = "order.create";

    public static final String ORDER_RESULT_EXCHANGE = "floworder.order.result.exchange";

    public static final String ORDER_RESULT_QUEUE = "floworder.order.result.queue";

    public static final String ORDER_RESULT_ROUTING_KEY = "order.create.result";

    public static final String ORDER_CREATE_COMMAND = "ORDER_CREATE_COMMAND";

    public static final String ORDER_CREATE_SUCCEEDED = "ORDER_CREATE_SUCCEEDED";

    public static final String ORDER_CREATE_FAILED = "ORDER_CREATE_FAILED";

    public static final String ORDER_CREATE_CONSUMER = "order-create-consumer";

    public static final String ORDER_RESULT_CONSUMER = "order-result-consumer";

    public static final String RESOURCE_SERVICE = "floworder-resource-service";

    public static final String ORDER_SERVICE = "floworder-order-service";

    public static final String ORDER_DLX = "floworder.order.dlx";

    public static final String ORDER_CREATE_DLQ = "floworder.order.create.dlq";

    public static final String ORDER_CREATE_DEAD_KEY = "order.create.dead";

    public static final String ORDER_RESULT_DLQ = "floworder.order.result.dlq";

    public static final String ORDER_RESULT_DEAD_KEY = "order.result.dead";

    private OrderMqConstant() {
    }
}
