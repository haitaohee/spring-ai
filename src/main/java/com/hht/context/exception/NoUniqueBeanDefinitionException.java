package com.hht.context.exception;

public class NoUniqueBeanDefinitionException extends BeansException{

    public NoUniqueBeanDefinitionException() {

    }

    public NoUniqueBeanDefinitionException(String message) {
        super(message);
    }

    public NoUniqueBeanDefinitionException(Throwable cause) {
        super(cause);
    }

    public NoUniqueBeanDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
