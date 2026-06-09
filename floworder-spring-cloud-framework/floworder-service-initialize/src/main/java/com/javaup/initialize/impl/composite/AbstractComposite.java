package com.javaup.initialize.impl.composite;

import java.util.*;

/**
 * 抽象类 AbstractComposite 表示组合接口，用于构建和执行具有树结构的业务逻辑
 **/
public abstract class AbstractComposite<T> {

    // 存放的是当前节点的子节点集合
    protected List<AbstractComposite<?>> children = new ArrayList<>();

    /**
     * 执行具体业务的抽象方法，由子类具体实现。
     */
    protected abstract void execute(T param);

    /**
     * 获取返回组件的类型
     */
    public abstract String type();

    /**
     * 当前节点父节点的编号；根节点返回 0 或 null
     */
    public abstract Integer executeParentOrder();

    /**
     * 当前节点在第几层
     */
    public abstract Integer executeTier();

    /**
     * 当前节点自己的编号
     */
    public abstract Integer executeOrder();

    /**
     * 存放当前节点的子节点
     */
    public void add(AbstractComposite<?> composite) {
        children.add(composite);
        children.sort(Comparator.comparingInt(node -> node.executeOrder() == null
                ? Integer.MAX_VALUE
                : node.executeOrder()));
    }

    @SuppressWarnings("unchecked")
    private void executeInternal(Object param) {
        execute((T) param);
    }

    public void allExecute(Object param) {
        Queue<AbstractComposite<?>> queue = new LinkedList<>();
        queue.add(this);

        while (!queue.isEmpty()) {
            AbstractComposite<?> current = queue.poll();
            if (current == null) {
                continue;
            }

            current.executeInternal(param);
            queue.addAll(current.children);
        }
    }
}
