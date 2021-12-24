---
theme: cyanosis
---
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
