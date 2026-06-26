package com.javaup.resource.dto;

import com.javaup.dto.OrderQueryDto;
import com.javaup.dto.ReservationRequestResultDto;
import com.javaup.resource.entity.MqOutboxEntity;
import com.javaup.resource.entity.StockDeductRecordEntity;
import com.javaup.resource.entity.StockItemEntity;
import com.javaup.resource.entity.UserReservationQuotaEntity;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ReservationRecoveryCheckResult {

    private String requestId;

    private ReservationRequestResultDto reservationRequest;

    private StockDeductRecordEntity deductRecord;

    private OrderQueryDto order;

    private String orderQueryError;

    private StockItemEntity stockItem;

    private UserReservationQuotaEntity quota;

    private Integer inventoryDiff;

    private Boolean inventoryInvariantOk;

    private List<MqOutboxEntity> resourceOutboxes = new ArrayList<>();

    private Long unresolvedDeadLetterCount;

    private List<String> warnings = new ArrayList<>();
}
