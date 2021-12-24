package com.example.demo;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

class Task extends Thread{
    SocketChannel tcpSocket;

    public Task(SocketChannel tcpSocket) {
        this.tcpSocket = tcpSocket;
    }
    @Override
    public void run() {
        try {
            System.out.println("run " + currentThread().getName());
            handleAAccept(tcpSocket);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private static void handleAAccept(SocketChannel tcpSocket) throws IOException {
        FileChannel fileChannel = FileChannel.open(Paths.get("2.png"), StandardOpenOption.WRITE, StandardOpenOption.CREATE);
        ByteBuffer buffer = ByteBuffer.allocate(1024);

        // 6.将客户端传递过来的图片保存在本地中
        int len=0;
        while ((len=tcpSocket.read(buffer)) != -1) {// 这种channel 只有在关闭的时候才会返回-1 文件是读完就返回-1了..
            System.out.println("len = " + len);
            buffer.flip();
            fileChannel.write(buffer);
            // 读完切换成写模式，能让管道继续读取文件的数据
            buffer.clear();
        }

        System.out.println(" 客户端没有数据要发送了 " );
        //+个功能：告诉客户端，已经收到了图片，以及这个图片很好
//        buffer.flip();
        buffer.put("这个图片收到了，好看".getBytes(StandardCharsets.UTF_8));
        buffer.flip();
        tcpSocket.write(buffer);
        buffer.clear();



        // 7.关闭通道
        fileChannel.close();//服务端先关闭通道
        tcpSocket.close();//也主动发送FIN包
    }
}
public class NioServer {

    public static void main(String[] args) throws IOException {
        ServerSocketChannel acceptSocket = ServerSocketChannel.open();
        acceptSocket.bind(new InetSocketAddress(6666));
        while (true){
            SocketChannel tcpSocket = acceptSocket.accept();
            Task task = new Task(tcpSocket);
            task.start();
        }
    }

}


