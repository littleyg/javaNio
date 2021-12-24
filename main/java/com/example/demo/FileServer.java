package com.example.demo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.*;

public class FileServer {

    //需要有个关于连接的数组来就记录size,acc
    static ConcurrentHashMap<SocketChannel, HashMap<String,Object>> channelMap = new ConcurrentHashMap<>();
    static ConcurrentHashMap<Future<?>,SocketChannel> tasks=new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {

        ServerSocketChannel serverSocket = ServerSocketChannel.open();
        serverSocket.bind(new InetSocketAddress(6666));
        serverSocket.configureBlocking(false);
        Selector selector = Selector.open();
        serverSocket.register(selector, SelectionKey.OP_ACCEPT);
        Acceptor acceptor = new Acceptor();
        Handler handler = new Handler();

        while (selector.select(1000)>=0){//假设一直阻塞,则任务可能得不到执行
            handleTasks();
            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()){
                SelectionKey key = iterator.next();
                iterator.remove();
                if (key.isAcceptable()){
                    acceptor.handleAccept(serverSocket, selector);
                }else if (key.isReadable()){
                    handler.readAndSend(key);
                }
            }
        }
    }

    static class Processor implements Callable<String> {
        HashMap<String,Object> prop;
        public Processor(HashMap<String, Object> prop) {
            this.prop = prop;
        }
        @Override
        public String call() throws Exception {
            String response = "完成收图" ;
            try {
                int millis = new Random().nextInt(3000);
                System.out.println("sleep millis " + millis);
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println(response);
            return response;
        }
    }
    static class Acceptor{
        public void handleAccept(ServerSocketChannel serverSocket, Selector selector) throws IOException {
            SocketChannel tcpSocket = serverSocket.accept();
            HashMap value = new HashMap();
            value.put("size",0L);
            value.put("acc",0L);
            channelMap.put(tcpSocket, value);
            tcpSocket.configureBlocking(false);
            tcpSocket.register(selector,SelectionKey.OP_READ);
            System.out.println("和客户端建立连接");
        }
    }

    static class Handler{
        static ExecutorService executorService = Executors.newFixedThreadPool(8);
        //read
        public static void readAndSend(SelectionKey key) throws IOException, ExecutionException, InterruptedException {
            SocketChannel tcpSocket =(SocketChannel) key.channel();
            HashMap<String,Object> prop = channelMap.get(tcpSocket);
            System.out.println("prop = " + prop);
            ByteBuffer buffer = ByteBuffer.allocate(1024*4);
            while (true){
                int read = tcpSocket.read(buffer);
                System.out.println("read = " + read);
                if (!(read >0)) break;
                buffer.flip();
                if (prop.get("size").equals(0L)){
                    long size = 0;
                    try {
                        size = buffer.getLong();
                    } catch (Exception e) {
                        System.out.println("需要按照指定的协议上传文件: size+文件");
                        tcpSocket.close();
                        break;
                    }
                    prop.put("size",size);
                    prop.put("fileChannel",FileChannel.open(Paths.get(size+".png"), StandardOpenOption.WRITE, StandardOpenOption.CREATE));
                }
                if ((Long)prop.get("size")>0L) {
                    FileChannel fileChannel =(FileChannel) prop.get("fileChannel");
                    fileChannel.write(buffer);
                    buffer.clear();
                    Long size = (Long)prop.get("size");
                    Long acc = (Long)prop.get("acc");
                    prop.put("acc",acc+read);
                    System.out.println("进度 = " + ((acc - 8) * 1.0 / size * 1.0) * 100);
                }
            }
            completed((Long)prop.get("size"), (Long)prop.get("acc"), tcpSocket,channelMap);
        }
        static void completed(Long size,Long acc,SocketChannel tcpSocket,ConcurrentHashMap<SocketChannel,HashMap<String,Object> > channelMap) throws IOException, ExecutionException, InterruptedException {
            if (acc == size +8) {
                HashMap<String,Object> prop = channelMap.get(tcpSocket);


                Future<String> future = executorService.submit(new Processor(prop));

                //再使用一个数组，将future和channelMap放进去，
                tasks.put(future,tcpSocket);
                handleTasks();
            }
        }


        //send
    }

    private static void handleTasks() throws InterruptedException, ExecutionException, IOException {
        //遍历task
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        for (Future<?> future1 : tasks.keySet()) {
            if (future1.isDone()){
                SocketChannel tcpSocket = tasks.get(future1);
                String o = future1.get().toString();
                buffer.put(o.getBytes());
                buffer.flip();

                tcpSocket.write(buffer);
                buffer.clear();

                //关闭文件
                //关闭流
                //释放资源


                FileChannel fileChannel = (FileChannel) (channelMap.get(tcpSocket)).get("fileChannel");

                fileChannel.close();
                tcpSocket.close();
                tasks.remove(future1);
                channelMap.remove(tcpSocket);
            }
        }
    }
}
