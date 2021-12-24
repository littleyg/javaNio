package com.example.demo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;

public class BioClient {
    public static void main(String[] args) throws IOException {
        SocketChannel tcpSocket = SocketChannel.open(new InetSocketAddress("127.0.0.1", 6666));
        FileChannel fileChannel = FileChannel.open(Paths.get("/Users/ywz/Documents/draw/http.png"), StandardOpenOption.READ);
//        ByteBuffer buffer = ByteBuffer.allocate(1024);
        long size = fileChannel.size();
        System.out.println("size = " + size);
        fileChannel.transferTo(0, size,tcpSocket);
//        while (fileChannel.read(buffer) != -1) {
//            buffer.flip();
//            tcpSocket.write(buffer);
//            buffer.clear();
//        }
        fileChannel.close();
        tcpSocket.close();
    }
}


