package com.enzoguimaraes.fluxo.charge;

import java.math.BigDecimal;
import java.util.UUID;

public record ChargePaymentData(UUID chargeId, BigDecimal amount, String currency) {
}
