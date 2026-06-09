package com.javaup.resource.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资源表实体 (fo_resource)
 */
@Data
@TableName("fo_resource")
public class ResourceEntity {

    /** 主键ID */
    private Long id;

    /** 资源编码 */
    private String resourceCode;

    /** 资源名称 */
    private String name;

    /** 资源描述 */
    private String description;

    /** 状态：1启用 0禁用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 逻辑删除：0未删除 1已删除 */
    private Integer deleted;
}