package io.netty.example.echo;

import java.nio.charset.Charset;

/**
 * TODO
 *
 * @author yo
 * @version 1.0.0
 * @since 2022/08/07 10:24
 */
public class Test {

    public static void main(String[] args) {
        System.out.println(Charset.defaultCharset());
        System.out.println("as".getBytes().length);
        System.out.println("啊你好ad".getBytes().length);
    }
}
