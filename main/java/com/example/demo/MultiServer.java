package com.example.demo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Random;

public class MultiServer {
    public static void main(String[] args) throws IOException {
        ServerSocketChannel serverSocket = ServerSocketChannel.open();
        serverSocket.bind(new InetSocketAddress(6666));
        serverSocket.configureBlocking(false);
        Selector selector = Selector.open();
        serverSocket.register(selector, SelectionKey.OP_ACCEPT);
        while (selector.select() > 0) {//select 阻塞表示没有事件，select返回ready events的个数
            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                iterator.remove();
                if (key.isAcceptable()) {
                    SocketChannel tcpSocket = serverSocket.accept();
                    tcpSocket.configureBlocking(false);
                    tcpSocket.register(selector, SelectionKey.OP_READ);
                    System.out.println("和客户端建立连接");
                } else if (key.isReadable()) {
                    try {
                        handle(key);
                    } catch (IOException e) {
                        key.channel().close();
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private static void handle( SelectionKey key) throws IOException {
        SocketChannel tcpSocket = (SocketChannel) key.channel();

        ByteBuffer buffer = ByteBuffer.allocate(1024);

//        int read = tcpSocket.read(buffer);
//        if (read ==-1){
//            key.channel().close();
//            return;
//        }
//        String received = getReceived(buffer);
        String received="";
        while ((tcpSocket.read(buffer) )> 0) {//>0就表示没有阻塞
            buffer.flip();
            received = new String(buffer.array(), 0, buffer.remaining());
            System.out.println("client说" + received);
            buffer.clear();
        }
        buffer.put(("我收到了" + received).getBytes(StandardCharsets.UTF_8));
        buffer.flip();
        tcpSocket.write(buffer);
        buffer.clear();
    }

    private static String getReceived(ByteBuffer buffer) {
        String received="";
        buffer.flip();
        received = new String(buffer.array(), 0, buffer.remaining());
        System.out.println("client说" + received);
        buffer.clear();
        return received;
    }
}
