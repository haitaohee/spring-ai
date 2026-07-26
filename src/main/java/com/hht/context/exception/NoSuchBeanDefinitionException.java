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
public class NoSuchBeanDefinitionException extends BeanDefinitionException{

    public NoSuchBeanDefinitionException() {
    }

    public NoSuchBeanDefinitionException(String message) {
        super(message);
    }

}
