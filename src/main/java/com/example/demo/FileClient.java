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
        buffer.flip();
        tcpSocket.write(buffer);
        buffer.clear();

//        writeFileToSocket(tcpSocket, fileChannel, buffer, selector);
        transferFileToSocket(tcpSocket, fileChannel, selector);
        System.out.println(" 已经发送完图片，等服务器的答复 " );
        while (selector.select() > 0) {
            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()) {
                SelectionKey selectionKey = iterator.next();
                iterator.remove();
                SocketChannel channel = (SocketChannel) selectionKey.channel();
                if (selectionKey.isReadable()) {
                    ByteBuffer responseBuffer = ByteBuffer.allocate(1024);
                    int readBytes = channel.read(responseBuffer);
                    if (readBytes > 0) {
                        responseBuffer.flip();
                        System.out.println("服务器回复了，"+new String(responseBuffer.array(), 0, readBytes));
                        channel.close();
                        fileChannel.close();
                        return;
                    }
                }else if (selectionKey.isWritable()){
                    //将剩下的没有写完的数据继续写完
//                    continueWriteFileToSocket(selectionKey,channel, fileChannel, buffer);
                    continueTransferFileToSocket(selectionKey,channel, fileChannel,selector);
                }
            }
        }
    }

    private static void writeFileToSocket(SocketChannel tcpSocket, FileChannel fileChannel, ByteBuffer buffer, Selector selector) throws IOException {
        while (fileChannel.read(buffer) != -1) {
            buffer.flip();
            int remaining = buffer.remaining();
            System.out.println("buffer.remaining() = " + remaining);
            int writed = tcpSocket.write(buffer);
            System.out.println("write = " + writed);
           if (writed<remaining){
               tcpSocket.register(selector,SelectionKey.OP_WRITE);
               break;
           }
            buffer.clear();
        }
    }


    private static void continueWriteFileToSocket(SelectionKey key,SocketChannel tcpSocket, FileChannel fileChannel, ByteBuffer buffer) throws IOException {
        //buffer在之前kennel被先get了一部分，position的位置已经发生了改变
        int writed = tcpSocket.write(buffer);
        if (writed<buffer.remaining()){
           return;
        }
        buffer.clear();
        //如果能写完，就可以继续往buffer里面put数据并继续发送到网上了
        while (fileChannel.read(buffer) != -1) {
            buffer.flip();
            int remaining = buffer.remaining();
            writed = tcpSocket.write(buffer);
            System.out.println("write = " + writed);
            if (writed<remaining){
                return;
            }else {
                buffer.clear();
            }
        }
        key.cancel();
    }

    static long position;
    private static void transferFileToSocket(SocketChannel tcpSocket, FileChannel fileChannel, Selector selector) throws IOException {
        long l=fileChannel.transferTo(position, fileChannel.size(), tcpSocket);
        position+=l;
        System.out.println("position = " + position);
        if (position<fileChannel.size()){
            //没写完
            tcpSocket.register(selector,SelectionKey.OP_WRITE|SelectionKey.OP_READ);
            System.out.println("selector.keys() = " + selector.keys());
        }
    }
    private static void continueTransferFileToSocket(SelectionKey key,SocketChannel tcpSocket, FileChannel fileChannel,Selector selector ) throws IOException {
        System.out.println("continueTransferFileToSocket");
        long l=fileChannel.transferTo(position, fileChannel.size(), tcpSocket);
        position+=l;
        System.out.println("position = " + position);
        if (position<fileChannel.size()){
            //没写完
            return;
        }
//        System.out.println("keys  = " + key.interestOps());
//        key.cancel();
////        tcpSocket
//        System.out.println("selector.keys() = " + selector.keys());

        tcpSocket.register(selector,SelectionKey.OP_READ);
//        System.out.println("selector.keys() = " + selector.keys().iterator().next().interestOps());
    }

}


