package com.enzoguimaraes.fluxo.charge;

public class InvalidChargeTransitionException extends RuntimeException {

    public InvalidChargeTransitionException() {
        super("Charge is not pending");
    }
}
