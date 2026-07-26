/*
 * Copyright (c) 2026, TP-LINK Co.,Ltd. All rights reserved.
 */
package com.hht.context.exception;

/**
 * Description of this file
 * @author admin
 * @version 1.0
 * @since 2026/7/26
 */
public class UnsatisfiedDependencyException extends BeanCreationException{

    public UnsatisfiedDependencyException() {
    }

    public UnsatisfiedDependencyException(String message) {
        super(message);
    }

    public UnsatisfiedDependencyException(String message, Throwable cause) {
        super(message, cause);
    }

    public UnsatisfiedDependencyException(Throwable cause) {
        super(cause);
    }
}
