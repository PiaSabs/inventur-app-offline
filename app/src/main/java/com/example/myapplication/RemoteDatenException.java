package com.example.myapplication;

public class RemoteDatenException extends Exception {

    public RemoteDatenException(String message) {
        super(message);
    }

    public RemoteDatenException(String message, Throwable cause) {
        super(message, cause);
    }
}
