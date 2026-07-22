package com.hht.context.io;

import java.io.IOException;
import java.io.InputStream;

/**
 * 读取 InputStream 的回调接口。
 * 将流的打开/关闭逻辑与具体处理逻辑分离，避免资源泄漏。
 * 这个接口用来实现处理逻辑，资源获取和关闭调用固定的方法，所以不会泄露
 */
@FunctionalInterface
public interface InputStreamCallback<T> {

    T doWithInputStream(InputStream input) throws IOException;
}
