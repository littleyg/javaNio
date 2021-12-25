package com.example.demo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

class BioServer{
    public static void main(String[] args) throws IOException {
        ServerSocketChannel acceptSocket = ServerSocketChannel.open();
        acceptSocket.bind(new InetSocketAddress(6666));
        while (true){
            SocketChannel tcpSocket = acceptSocket.accept();
            Task1 task = new Task1(tcpSocket);
            task.start();
        }
    }
}
class Task1 extends Thread {
    SocketChannel tcpSocket;

    public Task1(SocketChannel tcpSocket) {
        this.tcpSocket = tcpSocket;
    }

    @Override
    public void run() {
        try {
            handleAAccept(tcpSocket);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 将客户端传递过来的图片保存在本地中
    private static void handleAAccept(SocketChannel tcpSocket) throws IOException {
        FileChannel fileChannel = FileChannel.open(Paths.get("test.png"), StandardOpenOption.WRITE, StandardOpenOption.CREATE);
        //让客户端发不出来，则write或者transferTo会出现阻塞
        try {
            Thread.sleep(1000*100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
//        long l = fileChannel.transferFrom(tcpSocket, 0, 19300-2000);
//        System.out.println("接收到的数据是 = " + l);
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        int len=0;
        while ((len=tcpSocket.read(buffer)) != -1) {// 这种channel 只有在关闭的时候才会返回-1
            System.out.println("len = " + len);
//            buffer.flip();
//            fileChannel.write(buffer);
            buffer.clear(); // 读完切换成写模式，能让管道继续读取文件的数据
        }
        System.out.println("end while len = " + len);
    }
}