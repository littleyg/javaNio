我之前经常这么问自己，我真的懂JAVA IO和NIO吗？看着很简单，也经常使用，可是用过就忘了。

JAVA IO是指Java程序处理输入输出，现在通常是指文件和网络IO。NIO是JDK 1.4之后提供的新的IO方面的API。

Java IO和NIO虽然看起来很简单，但要说点什么出来，却似乎有什么如鲠在喉，无从说起。

本文从底层源码、理论图解和模型思考几个方面尝试**讲透JAVA IO和NIO**。
# Java IO
JDK 1.4之前提供了Java io包。

## Java IO API的设计特点
在java.io包下提供了很多的API，包括Stream和Reader、Writer等。按照给编程者交付的信息不同，这些分为两类：
- 字节流包括InputStream和 OutputStream。
  - InputStream是可读的，提供read函数，具体的实现有FileInputStream,SocketInputStream等
  - OutputStream可写的，提供write函数，具体的实现有FileInputStream,SocketInputStream等
- 字符流包括Reader和Writer。
  - Reader是可读的字符流，提供read函数，比如从shell终端读取输入，具体的实现有FileReader等
  - Writer是可写的字符流，提供write函数，具体的实现有Printer和FileWriter等


## Java IO API的底层原理
下面的这个例子将数据源读到的内容写入目的数组。
```java
FileInputStream fis = new FileInputStream(source);
FileOutputStream fos = new FileOutputStream(des);
byte[] bytes = new byte[1024 * 1024];
int len;
while ((len = fis.read(bytes)) != -1) {
    fos.write(bytes, 0, len);
}
```
- **fis是文件输入流**
- **fos是文件输出流**
- 文件流读完时，`fis.read`**返回-1**

追踪代码，发现FileInputStream的read函数底层调用的是：
```
private native int readBytes(byte b[], int off, int len) throws IOException;
```
这是native函数了，从Java代码没法看到具体的实现。通过查看openjdk的**c语言源码**，可以看到readBytes的实现：
```C
jint readBytes(JNIEnv *env, jobject this, jbyteArray bytes,  jint off, jint len, jfieldID fid)
{
    jint nread;
    char stackBuf[BUF_SIZE];
    char *buf = NULL;
    FD fd;
    if (IS_NULL(bytes)) {
        JNU_ThrowNullPointerException(env, NULL);
        return -1;
    }
    if (outOfBounds(env, off, len, bytes)) {
        JNU_ThrowByName(env, "java/lang/IndexOutOfBoundsException", NULL);
        return -1;
    }
    if (len == 0) {
        return 0;
    } else if (len > BUF_SIZE) {
        buf = malloc(len);
        if (buf == NULL) {
            JNU_ThrowOutOfMemoryError(env, NULL);
            return 0;
        }
    } else {
        buf = stackBuf;
    }
    fd = getFD(env, this, fid);
    if (fd == -1) {
        JNU_ThrowIOException(env, "Stream Closed");
        nread = -1;
    } else {
        nread = IO_Read(fd, buf, len);
        if (nread > 0) {
            (*env)->SetByteArrayRegion(env, bytes, off, nread, (jbyte *)buf);
        } else if (nread == -1) {
            JNU_ThrowIOExceptionWithLastError(env, "Read error");
        } else { /* EOF */
            nread = -1;
        }
    }
    if (buf != stackBuf) {
        free(buf);
    }
    return nread;
}
```
 C语言的函数参数比native函数多了`JNIEnv *env, jobject this`
 
可以看出，底层C语言代码的流程是：
- malloc一个len（**要read的长度**）长度的buf数组:` buf = malloc(len)`
- 系统调用read操作：`IO_Read(fd, buf, len)`
- 将buf数据**复制**到JVM的**bytes数组**:` (*env)->SetByteArrayRegion(env, bytes, off, nread, (jbyte *)buf)`

 FileOutputStream的write函数实现也是用到了malloc，系统调用write。那么，**为什么要malloc一块新的内存**出来做系统调用，而不是直接使用bytes数组来做系统调用呢？

这是**因为Linux提供的pread或者read等系统调用，只能操作一块固定的内存区域。这就意味着只能对direct memory进行操作**，而heap memory中的对象在经历gc后**内存地址会发生改变**，也就是**JVM heap区的内存**对于虚拟机外的操作来说**不是一块固定的区域**。

如下图：

![nio7.png](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/62b6809c97734ec28ee2fb7f9c5b3b02~tplv-k3u1fbpfcp-watermark.image?)

- **buf**:非JVM管理的内存，是**固定**的
- **bytes**:JVM中的heap对应的实际物理内存，因为垃圾收集的原因可能会移动
- read等系统调用：经过**两次上下文切换**，会将fd的内容先拷贝到内核态的缓冲区，再从内核态缓冲区拷贝到用户态的buf
- 最终buf的数据要拷贝到我们为FileInputStream读操作在heap堆申请的bytes数组里面去，完成Java程序读文件的功能。
## Java IO是阻塞的
我们知道系统调用read/write等是阻塞的。**阻塞**在文件读写例子的时候还不明显，网络读写会出现明显的阻塞，因为网络连接的缓冲区里面不一定有数据，有数据的时候则read阻塞结束返回数据。

对于网络IO是这样的：

![nio12.png](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/6d4dda941553486bb35523f513be9cb1~tplv-k3u1fbpfcp-watermark.image?)
## Java IO 的总结
上面讲到的Java IO，优点是api设计和用法简单，但也存在两个缺点：
- 提供的api是阻塞的，对应的底层逻辑是系统调用read/write等
- 比系统调用的开销还多一次内存分配和拷贝的过程

![nio11.png](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/be659202d8a64101836e0a5df20e0201~tplv-k3u1fbpfcp-watermark.image?)


# JAVA NIO
java IO的两个缺点开销很大，不能应对日益增长的高并发需求。
Java NIO的出现主要就是为了解决java IO的两个缺点，并满足日益增长的高并发需求。

本章会先简单描述nio api的设计特点，然后一步步阐述是怎么解决java IO的缺点的。以及满足高并发需求需要的设计，将在下一章进一步展开。
## NIO API的设计特点
NIO的数据结构主要是**Buffer**和**Channel**，可以先简单学习下，有个大概印象。（如果已经了解则可以跳过这个章节）
- **Buffer接口**，提供get/put等操作。实现该接口的类有ByteBuffer、LongBuffer和CharBuffer等，ByteBuffer的使用最广。
  - Buffer是一个存放数据的缓冲区，具体实现是提供position和limit等指针来对数据进行读写操作。
![nio2.png](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/983c78efdadd4d5484fac0f0374ec573~tplv-k3u1fbpfcp-watermark.image?)
- **Channel**接口是给Java程序提供read/write的接口api，具体的实现有：
  - 文件的 FileChannel
  - 网络的 SocketChannel、DatagramChannel
  - 管道：PipelineChannel
![nio3.png](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/404dc2b2bafb428ba974c9873aee60ba~tplv-k3u1fbpfcp-watermark.image?)



在NIO中，我们就是将**Buffer**和**Channel**两种数据结构组合在一起使用

![nio4.png](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/0c5f138bc6fb4534abb00d4cb7de984a~tplv-k3u1fbpfcp-watermark.image?)

具体表现在：
- 将**准备好**的ByteBuffer数据**写入channel**
- 将channel里**可用的数据读入ByteBuffer**

### 熟练使用Buffer
Buffer是一个**可读可写**有很多指针的数据结构，下图展示了对它的一系列操作：

![nio1.png](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/f7218a05cc6243debc5b103a88e5b4cd~tplv-k3u1fbpfcp-watermark.image?)
- 分配一个ByteBuffer`ByteBuffer.allocate(12)`后：position=0,cap=limit=12
  - 此时buffer为空，表示可以往里面写入数据
  - 当然要读也可以，只是读到的字节全是0，而且会改变position的值
- 写入数据`byteBuffer.put("hello".getBytes())`后： position=5,cap=limit=12
- flip操作```byteBuffer.flip()```之后：pos=0,cap=12，limit=5
  - 此时相当于改为可读的模式，position和limit之间的数据`[position,limit)`是可读的。
  - 通道写完要读的时候，记得flip
- 从通道读数据```byteBuffer.get(bytes)```将buffer数据读到一个字节数组（长度为2）里面,之后position指向2的位置
- 清理buffer```byteBuffer.clear();```之后，position等指针回到初始值，但是实际没有对buffer里面的变量进行任何改变
  - 准备往通道里面写数据的时候，一般记得先clear

### 深入理解Buffer
要理解NIO的Buffer，需要对Buffer的具体分配有清晰的理解。对于ByteBuffer，又有三种具体的实现：
- HeapByteBuffer
- DirectByteBuffer
- MappedByteBuffer
其实这几种的区别就是他们在内存的分配不同，如下图：
![nio5.png](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/f21f01c6c37b44309d1ad9eb715ef18c~tplv-k3u1fbpfcp-watermark.image?)
- HeapByteBuffer是分配在JVM 堆内存上
- DirectByteBuffer是使用的堆外内存
- MappedByteBuffer是针对IO的文件描述符而言，通过mmap系统调用将外设的地址映射到内存，


#### mmap
进程在用户空间调用库函数mmap，原型：void *mmap(void *start, size_t length, int prot, int flags, int fd, off_t offset);
![image.png](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/5c7592674aec411ab9737b7ffd1931b2~tplv-k3u1fbpfcp-watermark.image?)

在当前进程的虚拟地址空间中，寻找一段空闲的满足要求的连续的虚拟地址。mmap可以绕过读写文件的时候，内核态和用户态数据拷贝的过程


## NIO read实现的底层原理
下面举个具体的例子,还是用于文件的拷贝：
```java
FileChannel readChannel = fis.getChannel();
FileChannel writeChannel = fos.getChannel();
ByteBuffer byteBuffer = ByteBuffer.allocate(1024 * 1024);//1M缓冲区
while (readChannel.read(byteBuffer) != -1) {
    byteBuffer.flip();
    writeChannel.write(byteBuffer);
    byteBuffer.clear();
}
```
- `FileInputStream`可以通过`getChannel()`得到`FileChannel`
- `ByteBuffer.allocate(1024 * 1024)`分配的是`HeapByteBuffer`
那么，readChannel.read(byteBuffer)底层是怎么做的呢？

```Java
ByteBuffer bb = Util.getTemporaryDirectBuffer(dst.remaining());
int n = readIntoNativeBuffer(fd, bb, position, nd);
```
首先通过`getTemporaryDirectBuffer`分配了一个临时的DirectBuffer，最终调用了native方法`pread0` 
```java
static native int pread0(FileDescriptor fd, long address, int len,
                         long position) throws IOException;
```
C语言代码跟进去，发现，
```C
JNIEXPORT jint JNICALL
Java_sun_nio_ch_FileDispatcherImpl_pread0(JNIEnv *env, jclass clazz, jobject fdo,
                            jlong address, jint len, jlong offset)
{
    jint fd = fdval(env, fdo);
    void *buf = (void *)jlong_to_ptr(address);

    return convertReturnVal(env, pread64(fd, buf, len, offset), JNI_TRUE);
}
```
对于Linux系统，这里实际也是调用了pread或pread64等系统调用。
这里为何需要先分配一个`DirectByteBuffer`呢？其实这里和malloc是差不多的，而是由JVM直接管理这块内存。


![nio6.png](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/8663d669c55c4d778949c953e0ba2285~tplv-k3u1fbpfcp-watermark.image?)

这么看起来，和JAVA IO read的做法很相似。只是**原来的buf**换为这里的**tmp DirectByteBuffer**。
但假设这里的代码这么写，
```java
FileChannel readChannel = fis.getChannel();
FileChannel writeChannel = fos.getChannel();
ByteBuffer byteBuffer = ByteBuffer.allocateDirect(1024 * 1024);//1M缓冲区
while (readChannel.read(byteBuffer) != -1) {
    byteBuffer.flip();
    writeChannel.write(byteBuffer);
    byteBuffer.clear();
}
```
使用` ByteBuffer.allocateDirect(1024 * 1024)`分配一个DirectByteBuffer，减少了一次用户态到用户态的数据拷贝过程。
![nio8.png](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/7270d11ccd9b477e8bdde1e9ccc960a6~tplv-k3u1fbpfcp-watermark.image?)
## MappedByteBuffer
效率能否更高呢？MappedByteBuffer类就是为了进一步提升效率设计的。
```java
@Test
public void test() throws IOException {
    FileChannel inChannel = FileChannel.open(Paths.get("nio.dmg"), StandardOpenOption.READ);
    FileChannel outChannel = FileChannel.open(Paths.get("nio2.dmg"), StandardOpenOption.WRITE,StandardOpenOption.READ,StandardOpenOption.CREATE);
    System.out.println("outChannel = " + outChannel);

    long size = inChannel.size();
    System.out.println("size = " + size);
    MappedByteBuffer inMappedBuffer = inChannel.map(FileChannel.MapMode.READ_ONLY, 0, size);


    byte[] bytes = new byte[inMappedBuffer.limit()];
    inMappedBuffer.get(bytes);
    //可读的channel才可以建立mappedByteBuffer...
    MappedByteBuffer outMappedBuffer = outChannel.map(FileChannel.MapMode.READ_WRITE, 0, size);
    outMappedBuffer.put(bytes);
}
```
- 使用`MappedByteBuffer inMappedBuffer =inChannel.map(FileChannel.MapMode.READ_ONLY, 0, size)`从inChannel获得一个MappedByteBuffer
- 使用` MappedByteBuffer outMappedBuffer = outChannel.map(FileChannel.MapMode.READ_WRITE, 0, size);`也获得另外一个MappedByteBuffer
- 可以通过读取inMappedBuffer的数据，再put到outMappedBuffer里去

我们看看FileChannel的map函数：
```java
MappedByteBuffer map(MapMode mode, long position, long size){
    addr = map0(imode, mapPosition, mapSize);  
    FileDescriptor mfd;
    try {
        mfd = nd.duplicateForMapping(fd);
    } catch (IOException ioe) {
        unmap0(addr, mapSize);
        throw ioe;
    }
    int isize = (int)size;
    Unmapper um = new Unmapper(addr, mapSize, isize, mfd);
    if ((!writable) || (imode == MAP_RO)) {
        return Util.newMappedByteBufferR(isize,
                                         addr + pagePosition,
                                         mfd,
                                         um);
    } else {
        return Util.newMappedByteBuffer(isize,
                                        addr + pagePosition,
                                        mfd,
                                        um);
    }
}
```
最终调用的是```private native long map0(int prot, long position, long length)```函数，
 查看Open JDK C语言的Linux系统的实现，发现其实就是调用了`mmap64`。
```C
JNIEXPORT jlong JNICALL
Java_sun_nio_ch_FileChannelImpl_map0(JNIEnv *env, jobject this,
                                     jint prot, jlong off, jlong len, jboolean map_sync){
    void *mapAddress = 0;
    jobject fdo = (*env)->GetObjectField(env, this, chan_fd);
    jint fd = fdval(env, fdo);
    int protections = 0;
    int flags = 0;
    mapAddress = mmap64(
        0,                    /* Let OS decide location */
        len,                  /* Number of bytes to map */
        protections,          /* File permissions */
        flags,                /* Changes are shared */
        fd,                   /* File descriptor of mapped file */
        off);                 /* Offset into file */
    return ((jlong) (unsigned long) mapAddress);
}
```
![nio9.png](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/d55706e6351e43fca10d0bd0ec5f312e~tplv-k3u1fbpfcp-watermark.image?)

使用`Channel.map`分配的MappedByteBuffer,不仅没有用户态到用户态的数据拷贝过程，还减少了用户态到内核态的数据拷贝过程。
### transferTo等API
FileChannel还提供transferTo和transferFrom等API，进一步简化了文件拷贝的操作，如下例子
```java
@Test
public void test2() throws IOException {
    FileChannel inChannel = FileChannel.open(Paths.get("nio.dmg"), StandardOpenOption.READ);
    FileChannel outChannel = FileChannel.open(Paths.get("nio2.dmg"), StandardOpenOption.WRITE,StandardOpenOption.READ,StandardOpenOption.CREATE);
    inChannel.transferTo(0,inChannel.size(),outChannel);
}
```
- 底层是直接调用的`sendfile(srcFD, dstFD, position, &numBytes, NULL, 0)`来实现的
- transferTo可用于文件到文件的传输
- transferTo也可用于文件到socket的传输
- kafka使用了NIO的transferTo零拷贝用于网络和文件的拷贝

## NIO初总结
本节讲了NIO的一些基本用法和主要数据结构，包括：
- Buffer的各种操作
- FileChannel的read和write基本流程和实现原理；以及HeapByteBuf和DirectByteBuf的区别。
- FileChannel的MappedByteBuf的用法和实现原理
- FileChannel的transferTo的用法和原理
跟JAVA IO比起来，NIO通过Buffer的各种**增强设计**，已经达到减少数据拷贝次数的目的。

![nio14.png](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/0d43f5394a5544f8921a10923d6dd21d~tplv-k3u1fbpfcp-watermark.image?)

下文将详述NIO在网络通信中是怎么解决**阻塞的API**这个问题的。

---
theme: cyanosis
---
书接上文[深入思考Java IO和NIO（一）](https://juejin.cn/post/7044920986445021198/),本文将详述NIO在网络通信中是怎么解决**阻塞的API**这个问题的。


我们会一步步从一个文件服务器看到是怎么解决这个问题的。

# 初次实践
假设有一个文件服务器提供上传图片的服务，有海量的客户端的图片需要上传，我们应该怎么提供这个服务呢？

首先我打算使用TCP连接让客户端上传文件，每次上传文件建立一个连接，在一个连接里面客户端将一张图片发送完之后就断开连接。

有了这个想法后，看看我是怎么写这个文件服务器的。
## 文件服务server
可以看我在GitHub的代码 
- [BioServer](https://github.com/littleyg/javaNio/blob/main/main/java/com/example/demo/BioServer.java)
- [BioClient](https://github.com/littleyg/javaNio/blob/main/main/java/com/example/demo/BioClient.java)
```java
class BioServer{
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
class Task extends Thread{
    SocketChannel tcpSocket;
    public Task(SocketChannel tcpSocket) {
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
    FileChannel fileChannel = FileChannel.open(Paths.get("pic.png"), StandardOpenOption.WRITE, StandardOpenOption.CREATE);
    ByteBuffer buffer = ByteBuffer.allocate(1024);
    while (tcpSocket.read(buffer) != -1) {// 这种channel 只有在关闭的时候才会返回-1 
        buffer.flip();
        fileChannel.write(buffer);
        buffer.clear(); // 读完切换成写模式，能让管道继续读取文件的数据
    }
```
server端的代码就上这样
- `ServerSocketChannel.open()`开启一个ServerSocketChannel用于监听6666端口的连接
- while循环调用`acceptSocket.accept()`来监听是否有连接要建立。
  - accept是阻塞的
  - 连接建立成功，则将这个TCP连接交给Task线程去处理。
- Task读取tcpSocket连接发过来的信息并存储到文件。
在这个实现里面， 一个连接需要一个线程处理。
## 分析阻塞的系统调用
在这个实现里面， 一个连接需要一个线程处理,主要的原因是因为read/write是阻塞的。
### read的阻塞
>read从Channel读取字节序列到给定缓冲区。
  - tcpSocket.read(buffer)和前面一样，也是系统调用read等，是阻塞的，
  - 如果缓冲区有可用的数据，则返回可用的数据，尝试从通道读取最多 r 个字节，其中 r 是缓冲区中剩余的字节数，即 dst.remaining()。
  下图绿色部分上TCP上缓冲区应用程序可以读取的可用的数据：
  
  ![nio33.png](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/5e8cd7d2f1ef4013b737657785578c6c~tplv-k3u1fbpfcp-watermark.image?)
  - 当对方发起FIN包的时候，这个时候读完所有ACK的数据，read系统调用才会返回-1
    - ```tcpSocket.shutdownOutput();```,shutdown()系统调用的功能是关闭一个套接字的指定方向上的通信
    - ```tcpSocket.close();```   
    - 当RCV_BUF没有数据可读，且收到FIN包的时候，tcpSocket.read(buffer)返回值就是-1

对于read系统调用来说，阻塞和非阻塞的返回是不同的，如下图所示
![nio10.png](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/6182900868464d7fbf8fd7d4c4f7b7dd~tplv-k3u1fbpfcp-watermark.image?)


### write的阻塞
对于TCP来说如果这个可用窗口为0，则write操作会被阻塞。
![nio34.png](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/2ba0a98f2a3746508f6e5358ad0d157c~tplv-k3u1fbpfcp-watermark.image?)
>做了一个小实验，客户端不停的发数据，但是让server应用不接收数据。后面发送端的write操作就会被阻塞，，而且发现接收端的接收缓冲区可能是500K的大小。

大家也可以尝试一下这个实验。感受阻塞的write。

## 传输过程优化--使用零拷贝


就像前面分析的，NIO使用MappedByteBuffer大大优化了数据复制的过程。
![nio14.png](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/02dc2bf382d045758559ace21efc0b64~tplv-k3u1fbpfcp-watermark.image?)

我们在这个文件服务器里，如果约定好要传输的数据的大小，那么也可以使用MappedByteBuffer。

```java
 void handleAAccept(SocketChannel tcpSocket) throws IOException {
        FileChannel fileChannel = FileChannel.open(Paths.get("test.png"), StandardOpenOption.WRITE, StandardOpenOption.CREATE);
        fileChannel.transferFrom(tcpSocket,0,19300);
    }
```
将客户端传递过来的图片通过` fileChannel.transferFrom(tcpSocket,0,19300)`保存在本地，
同样，客户端发送图片的逻辑也可以简化为`fileChannel.transferTo(0, size,tcpSocket)`。
```Java
public class BioClient {
    public static void main(String[] args) throws IOException {
        SocketChannel tcpSocket = SocketChannel.open(new InetSocketAddress("127.0.0.1", 6666));
        FileChannel fileChannel = FileChannel.open(Paths.get("***/http.png"), StandardOpenOption.READ);
        long size = fileChannel.size();
        fileChannel.transferTo(0, size,tcpSocket);
        fileChannel.close();
        tcpSocket.close();
    }
}
```
### transferTo和transferFrom的阻塞
transferTo和transferFrom操作在这里也是阻塞的。
> transferFrom将字节从给定的可读字节通道传输到该通道的文件中。
- 如果是从文件到文件，阻塞还是有限的
- 从网络到文件到操作，可能因为等待数据阻塞很久
比如下图这种情况：
![nio32.png](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/ec21fa9c8b0e4ffb8461d6848ce2dfaf~tplv-k3u1fbpfcp-watermark.image?)
客户端发了1000字节，transferFrom拿走可用的800字节就返回了。

而下面这种情况：

![nio31.png](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/3586cbc9607644a3a98c03b2f0d12a18~tplv-k3u1fbpfcp-watermark.image?)
transferFrom会等待后面两个字节，除非客户端再发两个字节过来或者连接关闭。
  


## 初实现的总结-BIO模型

这个文件服务器的实现已经初具模型，能满足上传图片的要求，还使用零拷贝技术进行优化，这是一个**BIO模型**。

BIO也就是说阻塞的IO。阻塞虽然不占用CPU时间，但是非常占用线程。
但是每一个连接对应一个新的线程，对于C10K的并发来说，这样的模型是支撑不了的。

所以我能不能使用非阻塞的呢？

# 非阻塞的服务模型
在JAVA NIO里，可以通过设置参数将Channel的读写设置为非阻塞的。比如``` tcpSocket.configureBlocking(false);```，那么read在没有数据的时候就可以返回0，而不是阻塞着。



## reply服务器的非阻塞实现
先不考虑文件服务器，这里实现客户端和服务器的通话。如下图所示：

![nio35.png](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/e19e45d322714d18b6f7b61c9d51d340~tplv-k3u1fbpfcp-watermark.image?)

服务器读取客户端发过来的一个小的文字，并回复收到了。具体的代码是：
- [服务器代码](https://github.com/littleyg/javaNio/blob/main/src/main/java/com/example/demo/MultiServer.java)

- [客户端那边的代码](https://github.com/littleyg/javaNio/blob/main/main/java/com/example/demo/MultiClient.java)
部分代码如下：
```java
public class MultiServer {
    public static void main(String[] args) throws IOException {
        ServerSocketChannel serverSocket = ServerSocketChannel.open();
        serverSocket.bind(new InetSocketAddress(6666));
        serverSocket.configureBlocking(false);
        Selector selector = Selector.open();
        serverSocket.register(selector, SelectionKey.OP_ACCEPT);
        while (selector.select() > 0) {//select返回ready events的个数
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
                    }
                }
            }
        }
    }
    private static void handle( SelectionKey key) throws IOException {
        SocketChannel tcpSocket = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        String received="";
        int i=0;
        while ((i=tcpSocket.read(buffer) )> 0) {//>0就表示没有阻塞
            buffer.flip();
            received = new String(buffer.array(), 0, buffer.remaining());
            System.out.println("client说" + received);
            buffer.clear();
        }
        System.out.println("i = " + i);
        buffer.put(("我收到了" + received).getBytes(StandardCharsets.UTF_8));
        buffer.flip();
        tcpSocket.write(buffer);
        buffer.clear();
    }
}
```


可以看到效果，多个client和server在通话，而且**服务器只使用了一个线程**！
![截屏2021-12-24 上午8.47.34.png](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/ddf28b7f16e24a31bad22ae10b086f6d~tplv-k3u1fbpfcp-watermark.image?)
使用了selector的多路复用技术， 底层原理是epoll，而且java nio实现的是水平触发的epoll模式，需要使用
`SelectionKey key = iterator.next();
                iterator.remove();`移除event，表示这个事件会处理完毕。
 - 注册了` serverSocket.configureBlocking(false); serverSocket.register(selector, SelectionKey.OP_ACCEPT)`,
 - 对于建立的连接，又注册`tcpSocket.configureBlocking(false);tcpSocket.register(selector, SelectionKey.OP_READ);`
 - Selector会负责件监听**这些channel的这些注册的类型**的事件
   - key.isAcceptable()表示有可连接事件，使用SocketChannel tcpSocket = serverSocket.accept()则不会阻塞，可建立连接
   - key.isReadable()表示这个key有可读事件，使用read也不会阻塞，直接能读到数据，当然读完了就没有数据，再读时读到的数据长度变为0。
   - 同理write也是非阻塞的。
                    
              
### 注册OP_WRITE事件

``` int write(ByteBuffer src)```返回写的字节长度，在非阻塞模式下，即使没有将准备的buf的数据写完也会返回，比如返回0.
因为缓冲区没有可用空间，那么在写较大数据的时候需要注册OP_WRITE事件，当发送窗口可用时select会通知应用程序。
```java
if (writed<remaining){
    tcpSocket.register(selector,SelectionKey.OP_WRITE);
    break;
}
```

在被通知时，记得处理：

```java
...
if (selectionKey.isWritable()){
    //将剩下的没有写完的数据继续写完
    continueWriteFileToSocket(selectionKey,channel, fileChannel, buffer, selector);
}

private static void continueWriteFileToSocket(SelectionKey key,SocketChannel tcpSocket, FileChannel fileChannel, ByteBuffer buffer, Selector selector) throws IOException {
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
```
- 写完了之后要cancel。


## 非阻塞场景下的零拷贝
在非阻塞场景下，同样可以使用transferTo函数减少拷贝次数，但是也要注意上面的写的问题。
```java
     long position;
    void transferFileToSocket(SocketChannel tcpSocket, FileChannel fileChannel, Selector selector) throws IOException {
        long l=fileChannel.transferTo(position, fileChannel.size(), tcpSocket);
        position+=l;
        System.out.println("position = " + position);
        if (position<fileChannel.size()){
            //没写完
            tcpSocket.register(selector,SelectionKey.OP_WRITE|SelectionKey.OP_READ);
            System.out.println("selector.keys() = " + selector.keys());
        }
    }
    void continueTransferFileToSocket(SelectionKey key,SocketChannel tcpSocket, FileChannel fileChannel,Selector selector ) throws IOException {
        System.out.println("continueTransferFileToSocket");
        long l=fileChannel.transferTo(position, fileChannel.size(), tcpSocket);
        position+=l;
        System.out.println("position = " + position);
        if (position<fileChannel.size()){
            //没写完
            return;
        }
      
       tcpSocket.register(selector,SelectionKey.OP_READ);
  
    }
```
这里这个Channel还注册了读的事件，所以不用cancel取消这个key.
- 注册写的时候 重新注册SelectionKey.OP_WRITE|SelectionKey.OP_READ
- 后面取消写的时候重新注册SelectionKey.OP_READ

至此，NIO不仅解决了多次数据复制的问题，还解决了阻塞的API的问题。

![nio38.png](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/48c5a0e97abc485f94e3637308ba4bfb~tplv-k3u1fbpfcp-watermark.image?)
## 正确的关闭连接
在服务器处理的时候，一定**要注意连接的处理**。因为客户端可以随时关闭连接。如果因为一个客户端的关闭导致服务器宕机，那还会影响其他的服务。
比如，此时强制关闭一个client：
![截屏2021-12-24 上午8.43.27.png](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/faf15ce3c5f14ca387aaf1e096d3c139~tplv-k3u1fbpfcp-watermark.image?)
此时服务端显示：
![截屏2021-12-24 上午8.43.13.png](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/f14de9987fe142019979a674712136da~tplv-k3u1fbpfcp-watermark.image?)
造成服务器的IOException异常退出，不过整个处理过程可以catch异常。如果没有catch，server将异常退出，这影响了服务器的服务。

抓包看服务器的6666端口：

```shell
# tcpdump -i any port 6666
localhost.49775 > localhost.6666: Flags [S], seq 3053657778, win 65535, options [mss 16324,nop,wscale 6,nop,nop,TS val 1370645599 ecr 0,sackOK,eol], length 0
localhost.6666 > localhost.49775: Flags [S.], seq 3892119155, ack 3053657779, win 65535, options [mss 16324,nop,wscale 6,nop,nop,TS val 1370645599 ecr 1370645599,sackOK,eol], length 0
localhost.49775 > localhost.6666: Flags [.], ack 1, win 6371, options [nop,nop,TS val 1370645599 ecr 1370645599], length 0
localhost.6666 > localhost.49775: Flags [.], ack 1, win 6371, options [nop,nop,TS val 1370645599 ecr 1370645599], length 0
localhost.49775 > localhost.6666: Flags [P.], seq 1:4, ack 1, win 6371, options [nop,nop,TS val 1370647611 ecr 1370645599], length 3
localhost.6666 > localhost.49775: Flags [.], ack 4, win 6371, options [nop,nop,TS val 1370647611 ecr 1370647611], length 0
localhost.6666 > localhost.49775: Flags [P.], seq 1:16, ack 4, win 6371, options [nop,nop,TS val 1370647612 ecr 1370647611], length 15
localhost.49775 > localhost.6666: Flags [.], ack 16, win 6371, options [nop,nop,TS val 1370647612 ecr 1370647612], length 0

localhost.49775 > localhost.6666: Flags [F.], seq 4, ack 16, win 6371, options [nop,nop,TS val 1371319563 ecr 1370647612], length 0
 localhost.6666 > localhost.49775: Flags [.], ack 5, win 6371, options [nop,nop,TS val 1371319563 ecr 1371319563], length 0
 localhost.6666 > localhost.49775: Flags [P.], seq 16:28, ack 5, win 6371, options [nop,nop,TS val 1371319573 ecr 1371319563], length 12
 localhost.49775 > localhost.6666: Flags [R], seq 3053657783, win 0, length 0
```
首先是三次握手完成：
- [S]client 发起syn包,seq=3053657778,win=65535
- [S.] server ack=3053657779,seq=3892119155,win=65535
- [.] client ack=1  win 6371
然后发送数据：一发一收；
最后客户端关闭连接：
- 客户端发送FIN
- 服务器内核回复ACK
- 服务器应用程序还在继续发数据，并没有也关闭连接
- 客户端内核发Reset包，client强制关闭了连接。




服务端应该正确的处理连接的关闭，比如像下面这样：
```java
 SocketChannel tcpSocket = (SocketChannel) key.channel();
    ByteBuffer buffer = ByteBuffer.allocate(1024);
    int read = tcpSocket.read(buffer);
    if (read ==-1){
        key.channel().close();
        return;
    }
}
```
假设tcpSocket.read返回-1 ，则表示收到了一个FIN包，则服务端也关闭连接。
这就是完美的四次挥手过程。如下显示
```shell
 # tcpdump -i any -nn -s0 -v --absolute-tcp-sequence-numbers port 6666
1.51461 > ::1.6666: Flags [F.], cksum 0x0028 (incorrect -> 0x5f54), seq 220187003, ack 2240511794, win 6371, options [nop,nop,TS val 1372377293 ecr 1372375988], length 0
1.6666 > ::1.51461: Flags [.], cksum 0x0028 (incorrect -> 0x5a3b), ack 220187004, win 6371, options [nop,nop,TS val 1372377293 ecr 1372377293], length 0
1.6666 > ::1.51461: Flags [F.], cksum 0x0028 (incorrect -> 0x5a3a), seq 2240511794, ack 220187004, win 6371, options [nop,nop,TS val 1372377293 ecr 1372377293], length 0
1.51461 > ::1.6666: Flags [.], cksum 0x0028 (incorrect -> 0x5a3a), ack 2240511795, win 6371, options [nop,nop,TS val 1372377293 ecr 1372377293], length 0
```
然后那条连接就处于TIME_WAIT状态了。
```
tcp6       0      0  localhost.51947      localhost.6666     TIME_WAIT
```
 TIME_WAIT只需要等待两个MSL,看起来大概是2S左右。

之前是**客户端发Reset强制关闭连接**的，那连接就直接释放了，此时服务端再发数据可能就有些不能预料的异常了。

## IO复用模型的总结-reactor单线程模型
通过引入Selector，一个线程就能完成所有的连接和客户端通信。
但是在我们这样的实现下，还是有一些缺点：
- 客户端之间相互影响，如果有一个客户端卡太久，会影响新的连接
- 但是不能很好地利用多核cpu 

其实，上面举的这个例子就是单线程reactor模型，像redis就是使用的这种模型，因为redis使用的场景get/set都是内存的操作，速度非常快，使用单线程reactor模型能够达到高性能。

server Reactor 单线程处理模型图如下
![nio36.png](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/0d8b1f42bf3645c0b78d35a6daaa563b~tplv-k3u1fbpfcp-watermark.image?)


## 文件服务器：reactor多线程模型
假设我们要用Reactor单线程模型来实现文件的传输，我们并不知道文件的大小，接收数据的时候并不知道什么时候接收完一张图片了。
客户端和服务器之间需要做一些约定：
- 发送端先约定图片的大小
- 接收端的连接需要在接收到这么大的一个文件之后，才将这个全部写到文件里面，才算这次图片传输真的结束了。
- 另外接收完图片之后，**验证压缩水印处理**可能要花很多的时间，我们需要有一个线程池来帮助处理这些，不能让业务处理占用连接和网络读写的时间，使得服务之间相互影响。
### 带有Process线程池的实现
- [服务端代码](https://github.com/littleyg/javaNio/blob/main/src/main/java/com/example/demo/FileServer.java)
- [客户端代码](https://github.com/littleyg/javaNio/blob/main/src/main/java/com/example/demo/FileClient.java)
使用一个线程池来帮助处理业务`Executors.newFixedThreadPool(8)`
```java
    static ExecutorService executorService = Executors.newFixedThreadPool(8);
    //需要有个关于连接的数组来就记录size,acc
    static ConcurrentHashMap<SocketChannel, HashMap<String,Object>> channelMap = new ConcurrentHashMap<>();
      static ConcurrentHashMap<Future<?>,SocketChannel> tasks=new ConcurrentHashMap<>();
    
       if (key.isReadable()){
                    SocketChannel tcpSocket =(SocketChannel) key.channel();
                    HashMap<String,Object> prop = channelMap.get(tcpSocket);
                    ByteBuffer buffer = ByteBuffer.allocate(1024*4);
                    while (true){
                        int read = tcpSocket.read(buffer);
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

  

    private static void completed(Long size,Long acc,SocketChannel tcpSocket,ConcurrentHashMap<SocketChannel,HashMap<String,Object> > channelMap) throws IOException, ExecutionException, InterruptedException {
        if (acc == size +8) {
            HashMap<String,Object> prop = channelMap.get(tcpSocket);
            Future<String> future = executorService.submit(new Processor(prop));
            //再使用一个数组，将future和channelMap放进去，
            tasks.put(future,tcpSocket);
            handleTasks();
        }
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
                //关闭文件 关闭 释放资源。。。
                tasks.remove(future1);
            }
        }
    }
}
class Processor implements Callable<String> {
    public String call() throws Exception {
          // 假设在处理业务
          int millis = new Random().nextInt(3000);
            System.out.println("sleep millis " + millis);
            Thread.sleep(millis);
        return "完成收图" ;
    }
}
```
要做到IO复用，需要一个数组保存文件的基本属性（比如大小、名字和上传进度等）和连接的对应关系；
使用了线程池之后，任务的执行会变得非常复杂
- 等待业务逻辑执行完毕才返回数据给客户端
``` java
Future<String> future = executorService.submit(new Processor(prop));
            //再使用一个数组，将future和channelMap放进去，
            tasks.put(future,tcpSocket);
```
- 需要统一处理task,不知道任务什么时候会处理完，需要select超时阻塞也要再调用task，比如
```java
while (selector.select(1000)>=0){//假设一直阻塞,则任务可能得不到执行
    handleTasks();
```
  select的超时时间是多少可能需要根据业务场景来具体指定，比如10ms或者1ms。
  
  
### 更优雅的封装

上面的代码实现很复杂，单一职责原则将代码拆分为**Reactor类**、**Acceptor类**和**Handler类**，这就是下面的模型了，就是Reactor 多线程处理模型。
 
![nio37.png](https://p6-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/e0078fbcf2af4ae783914ef85cbd7a28~tplv-k3u1fbpfcp-watermark.image?)


 
 # 总结
本文通过一个具体的例子，一步一步阐述了NIO在网络通信中是怎么解决**阻塞的API**这个问题的， 解决阻塞的问题带来了很多性能的好处，但也带来了编程和理解上的一些复杂性。

并且为了提高文件服务器的鲁棒性和高可用性，也使用了Reactor 多线程处理模型。

 







