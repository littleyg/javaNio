package com.example.demo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;

public class FileClient {
    public static void main(String[] args) throws IOException {
        SocketChannel tcpSocket = SocketChannel.open(new InetSocketAddress("127.0.0.1", 6666));
        tcpSocket.configureBlocking(false);
        FileChannel fileChannel = FileChannel.open(Paths.get("/Users/ywz/Documents/draw/http.png"), StandardOpenOption.READ);
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        System.out.println(" 发一张图片给服务器 " );
        Selector selector = Selector.open();
        tcpSocket.register(selector, SelectionKey.OP_READ);
        long size = fileChannel.size();
        System.out.println("size = " + size);
        buffer.putLong(size);
//        System.out.println("lb.position() = " + buffer.position());
        buffer.flip();
        tcpSocket.write(buffer);
        buffer.clear();
        while (fileChannel.read(buffer) != -1) {
            buffer.flip();
            int remaining = buffer.remaining();
            System.out.println("buffer.remaining() = " + remaining);
            int writed = tcpSocket.write(buffer);

            System.out.println("write = " + writed);
           if (writed<remaining){
               //还要继续写完的。。不然直接clear就可能导致会有问题
           }
            buffer.clear();
        }
        System.out.println(" 已经发送完图片，等服务器的答复 " );
        fileChannel.close();
        while (selector.select() > 0) {
            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()) {
                SelectionKey selectionKey = iterator.next();
                iterator.remove();
                if (selectionKey.isReadable()) {
                    SocketChannel channel = (SocketChannel) selectionKey.channel();
                    ByteBuffer responseBuffer = ByteBuffer.allocate(1024);
                    int readBytes = channel.read(responseBuffer);
                    if (readBytes > 0) {
                        responseBuffer.flip();
                        System.out.println("服务器回复了，"+new String(responseBuffer.array(), 0, readBytes));
                        channel.close();
                        return;
                    }
                }
            }
        }
    }
}


