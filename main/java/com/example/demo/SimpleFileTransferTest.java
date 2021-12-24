package com.example.demo;


import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

public class SimpleFileTransferTest {

    private long transferFile(File source, File des) throws IOException {
        long startTime = System.currentTimeMillis();

        if (!des.exists())
            des.createNewFile();

        FileInputStream in = new FileInputStream(source);
        FileOutputStream out = new FileOutputStream(des);

        //将数据源读到的内容写入目的地--使用数组
        byte[] bytes = new byte[1024 * 1024*10];
        int len;
        while ((len = in.read(bytes)) != -1) {
            System.out.println("len = " + len);
            out.write(bytes, 0, len);
        }
        System.out.println("len = " + len);

        long endTime = System.currentTimeMillis();
        return endTime - startTime;
    }

    private long transferFileWithNIO(File source, File des) throws IOException {
        long startTime = System.currentTimeMillis();

        if (!des.exists())
            des.createNewFile();

        RandomAccessFile read = new RandomAccessFile(source, "rw");
        RandomAccessFile write = new RandomAccessFile(des, "rw");

        FileChannel readChannel = read.getChannel();
        FileChannel writeChannel = write.getChannel();


//        ByteBuffer byteBuffer = ByteBuffer.allocate(1024 * 1024);//1M缓冲区 758：普通字节流时间 591：NIO时间
        ByteBuffer byteBuffer = ByteBuffer.allocate(1024 * 1024*10 );//10M缓冲区, 1077 vs 420; 100M:


        int len =0;
        while ((len=readChannel.read(byteBuffer)) > 0) {//实际也是用了directBuffer来实现的
            System.out.println("len = " + len);
            byteBuffer.flip();//转换方向
            writeChannel.write(byteBuffer);
            byteBuffer.clear();//清理干净
        }
        System.out.println("len = " + len);

        writeChannel.close();
        readChannel.close();
        long endTime = System.currentTimeMillis();
        return endTime - startTime;
    }



    public static void directReadWrite() {
        int time = 1000*1000;
        long start = System.currentTimeMillis();
        ByteBuffer buffer = ByteBuffer.allocate(4*time);
        for(int i=0;i<time;i++){
            buffer.putInt(i);
        }
        buffer.flip();
        for(int i=0;i<time;i++){
            buffer.getInt();
        }
        System.out.println("堆缓冲区读写耗时  ："+(System.currentTimeMillis()-start));

        start = System.currentTimeMillis();
        ByteBuffer buffer2 = ByteBuffer.allocateDirect(4*time);
        for(int i=0;i<time;i++){
            buffer2.putInt(i);
        }
        buffer2.flip();
        for(int i=0;i<time;i++){
            buffer2.getInt();
        }
        System.out.println("直接缓冲区读写耗时："+(System.currentTimeMillis()-start));
    }

    public static void directAllocate()  {
        int time = 10000000;
        long start = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            ByteBuffer buffer = ByteBuffer.allocate(4*time);
        }
        System.out.println("堆缓冲区创建时间："+(System.currentTimeMillis()-start));

        start = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(4*time);
        }
        System.out.println("直接缓冲区创建时间："+(System.currentTimeMillis()-start));
    }

    public static void main2(String[] args) {
        directAllocate();
    }



    public static void main(String[] args) throws IOException {
        SimpleFileTransferTest simpleFileTransferTest = new SimpleFileTransferTest();
        File sourse = new File("../../Downloads/test2.dmg");
        File des = new File("./io.dmg");
        File nio = new File("./nio.dmg");

        long time = simpleFileTransferTest.transferFile(sourse, des);
        System.out.println(time + "：普通字节流时间");

        long timeNio = simpleFileTransferTest.transferFileWithNIO(sourse, nio);
        System.out.println(timeNio + "：NIO时间");
        //nio的时间还是快不少的。。 真是奇怪了，不是说文件不会变得更快的吗


    }

}
