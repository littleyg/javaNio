package com.example.demo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
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
        FileChannel fileChannel = FileChannel.open(Paths.get("/Users/ywz/Downloads/test2.dmg"), StandardOpenOption.READ);
        MappedByteBuffer mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size());
        ByteBuffer buffer = ByteBuffer.allocate(1024*40);
        long size = fileChannel.size();
//
        long l = fileChannel.transferTo(0, size, tcpSocket);
        System.out.println("size = " + l);
        int len=0;
//        while (fileChannel.read(buffer) != -1) {
//            buffer.flip();
//            int write = tcpSocket.write(buffer);
//            len+=write;
//            System.out.println("发送了：  " + len/1000.0+" K字节");
//            buffer.clear();
//
//        }
//        try {
//            Thread.sleep(1000*10);
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
//        System.out.println("sleep结束");
//        System.out.println("size = " + size);
        fileChannel.close();
        tcpSocket.close();
    }
}


