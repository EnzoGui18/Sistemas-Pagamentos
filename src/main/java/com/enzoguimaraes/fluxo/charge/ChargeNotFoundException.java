package com.enzoguimaraes.fluxo.charge;

public class ChargeNotFoundException extends RuntimeException {

    public ChargeNotFoundException() {
        super("Charge not found");
    }
}
