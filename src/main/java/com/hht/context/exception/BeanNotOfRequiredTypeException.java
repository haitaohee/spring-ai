package com.hht.context.exception;

public class BeanNotOfRequiredTypeException extends BeansException{

    public BeanNotOfRequiredTypeException() {

    }

    public BeanNotOfRequiredTypeException(String message) {
        super(message);
    }

    public BeanNotOfRequiredTypeException(Throwable cause) {
        super(cause);
    }

    public BeanNotOfRequiredTypeException(String message, Throwable cause) {
        super(message, cause);
    }
}
