package com.javaup.initialize.impl.composite;

import com.javaup.enums.BaseCodeEnum;
import com.javaup.exception.FlowOrderFrameException;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 组合模式容器
 **/
public class CompositeContainer {

    // 这个集合存放各个树的根节点
    private final Map<String, AbstractComposite<?>> allCompositeInterfaceMap = new HashMap<>();

    @SuppressWarnings({"rawtypes","unchecked"})
    public void init(ConfigurableApplicationContext applicationContext){
        Map<String, AbstractComposite<?>> compositeInterfaceMap = (Map)applicationContext.getBeansOfType(AbstractComposite.class);
        Map<String, List<AbstractComposite<?>>> groupedByType = compositeInterfaceMap.values().stream()
                .map(composite -> (AbstractComposite<?>)composite)
                .collect(Collectors.groupingBy(AbstractComposite::type));
        groupedByType.forEach((type, components) -> {
            // 按照 key 进行建树，然后返回根节点
            AbstractComposite<?> root = build(components);
            // 如果根节点不为null，存入集合
            if (root != null) {
                allCompositeInterfaceMap.put(type, root);
            }
        });
    }

    public AbstractComposite<?> build(List<AbstractComposite<?>> components) {
        TreeMap<Integer, TreeMap<Integer, AbstractComposite<?>>> groupedByTier = new TreeMap<>();

        for (AbstractComposite<?> component : components) {
            if (component.executeTier() == null || component.executeOrder() == null) {
                throw new IllegalStateException("composite tier/order cannot be null: " + component.getClass().getName());
            }

            TreeMap<Integer, AbstractComposite<?>> tierMap =
                    groupedByTier.computeIfAbsent(component.executeTier(), k -> new TreeMap<>());

            AbstractComposite<?> old = tierMap.put(component.executeOrder(), component);
            if (old != null) {
                throw new IllegalStateException("duplicate executeOrder in same tier: tier="
                        + component.executeTier() + ", order=" + component.executeOrder());
            }
        }

        if (groupedByTier.isEmpty()) {
            return null;
        }

        buildTree(groupedByTier);

        Integer firstTier = groupedByTier.firstKey();
        List<AbstractComposite<?>> roots = groupedByTier.get(firstTier).values()
                .stream()
                .filter(component -> component.executeParentOrder() == null || component.executeParentOrder() == 0)
                .toList();

        if (roots.size() != 1) {
            throw new IllegalStateException("composite tree must have exactly one root, actual=" + roots.size());
        }

        return roots.get(0);
    }

    /**
     * 真正开始建树
     */
    private void buildTree(TreeMap<Integer, TreeMap<Integer, AbstractComposite<?>>> groupedByTier) {
        List<Integer> tiers = new ArrayList<>(groupedByTier.keySet());

        for (int i = 0; i < tiers.size() - 1; i++) {
            Integer currentTier = tiers.get(i);
            Integer nextTier = tiers.get(i + 1);

            if (!Objects.equals(nextTier, currentTier + 1)) {
                throw new IllegalStateException("composite tier must be continuous: " + currentTier + " -> " + nextTier);
            }

            TreeMap<Integer, AbstractComposite<?>> currentLevel = groupedByTier.get(currentTier);
            TreeMap<Integer, AbstractComposite<?>> nextLevel = groupedByTier.get(nextTier);

            for (AbstractComposite<?> child : nextLevel.values()) {
                Integer parentOrder = child.executeParentOrder();
                if (parentOrder == null || parentOrder == 0) {
                    continue;
                }

                AbstractComposite<?> parent = currentLevel.get(parentOrder);
                if (parent == null) {
                    throw new IllegalStateException("parent composite not found: child="
                            + child.getClass().getName() + ", parentOrder=" + parentOrder);
                }

                parent.add(child);
            }
        }
    }

    /**
     * 执行购买前的校验
     */
    public void execute(String type, Object param) {
        AbstractComposite<?> composite = Optional.ofNullable(allCompositeInterfaceMap.get(type))
                .orElseThrow(() -> new FlowOrderFrameException(BaseCodeEnum.COMPOSITE_NOT_EXIST));

        composite.allExecute(param);
    }
}

