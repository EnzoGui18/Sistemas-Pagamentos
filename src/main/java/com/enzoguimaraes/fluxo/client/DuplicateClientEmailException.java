package com.enzoguimaraes.fluxo.client;

public class DuplicateClientEmailException extends RuntimeException {

    public DuplicateClientEmailException() {
        super("A client with this email already exists");
    }
}
