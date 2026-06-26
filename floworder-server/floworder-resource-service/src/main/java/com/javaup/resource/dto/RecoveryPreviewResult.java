package com.javaup.resource.dto;

import com.javaup.dto.MqDeadLetterAdminDto;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RecoveryPreviewResult {

    private String actionRequestId;

    private String actionType;

    private String targetType;

    private String targetKey;

    private Boolean canExecute;

    private Integer currentStatus;

    private String recommendedAction;

    private List<String> effects = new ArrayList<>();

    private List<String> warnings = new ArrayList<>();

    private MqDeadLetterAdminDto deadLetter;
}
