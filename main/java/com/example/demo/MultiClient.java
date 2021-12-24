package com.example.demo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;

public class MultiClient {
    static int seq=1;
    public static void main(String[] args) throws IOException {
        SocketChannel tcpSocket = SocketChannel.open(new InetSocketAddress("127.0.0.1", 6666));
        try {
            tcpSocket.configureBlocking(false);
            Selector selector = Selector.open();
            tcpSocket.register(selector, SelectionKey.OP_READ);
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            buffer.put(("hello"+seq++).getBytes(StandardCharsets.UTF_8));
            buffer.flip();
            tcpSocket.write(buffer);
            buffer.clear();

            while (selector.select() > 0) selectHandle(tcpSocket, selector);
        } finally {
            tcpSocket.close();
        }
    }
    private static void selectHandle(SocketChannel tcpSocket, Selector selector) throws IOException {
        Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
        while (iterator.hasNext()) {
            SelectionKey selectionKey = iterator.next();
            if (selectionKey.isReadable()) {
                SocketChannel channel = (SocketChannel) selectionKey.channel();
                ByteBuffer responseBuffer = ByteBuffer.allocate(1024);
                int readBytes = channel.read(responseBuffer);
                if (readBytes > 0) {
                    responseBuffer.flip();
                    System.out.println("服务器说" + new String(responseBuffer.array(), 0, readBytes));
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    responseBuffer.clear();
                    responseBuffer.put(("hello"+seq++).getBytes(StandardCharsets.UTF_8));
                    responseBuffer.flip();
                    tcpSocket.write(responseBuffer);
                    responseBuffer.clear();
                }
            }
            iterator.remove();
        }
    }
}


