package com.enzoguimaraes.fluxo.charge;

public class InvalidChargeDueDateException extends RuntimeException {

    public InvalidChargeDueDateException() {
        super("Due date must be today or in the future");
    }
}
