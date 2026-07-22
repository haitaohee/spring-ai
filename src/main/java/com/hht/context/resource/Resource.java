package com.hht.context.resource;

//需要注入容器的资源，路径和name，比如扫描指定包后得到资源路径和全类名，创建对象注入容器
public record Resource(String path, String name) {


}
