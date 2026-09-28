package com.enzoguimaraes.fluxo.charge;

import java.util.List;

public record ChargePageResponse(
        List<ChargeResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
