package ideal.sylph.common.jvm;

import ideal.sylph.common.base.ObjectInputStreamProxy;
import ideal.sylph.common.base.Serializables;
import ideal.sylph.common.base.Throwables;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class JVMLauncher<R extends Serializable>
{
    private VmCallable<R> callable;
    private Process process;
    private Collection<URL> userJars;
    private Consumer<String> consoleHandler;

    public JVMLauncher(VmCallable<R> callable, Consumer<String> consoleHandler, Collection<URL> userJars)
    {
        this.callable = callable;
        this.userJars = userJars;
        this.consoleHandler = consoleHandler;
    }

    public VmFuture<R> startAndGet()
            throws IOException, ClassNotFoundException, JVMException
    {
        return startAndGet(null);
    }

    public VmFuture<R> startAndGet(ClassLoader classLoader)
            throws IOException, ClassNotFoundException, JVMException
    {
        byte[] bytes = startAndGetByte();
        VmFuture<R> vmFuture = (VmFuture<R>) Serializables.byteToObject(bytes, classLoader);
        if (!vmFuture.get().isPresent()) {
            throw new JVMException(vmFuture.getOnFailure());
        }
        return vmFuture;
    }

    private byte[] startAndGetByte()
            throws IOException
    {
        try (ServerSocket sock = new ServerSocket()) {
            sock.bind(new InetSocketAddress(InetAddress.getLocalHost(), 0));
            ProcessBuilder builder = new ProcessBuilder(buildMainArg(sock.getLocalPort()))
                    .redirectErrorStream(true);

            this.process = builder.start();
            try (OutputStream os = new BufferedOutputStream(process.getOutputStream())) {
                os.write(Serializables.serialize(callable));  //把当前对象 发送到编译进程
            }
            //IOUtils.copyBytes();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    consoleHandler.accept(line);
                }
            }

            try (Socket client = sock.accept()) {
                try (InputStream input = client.getInputStream()) {
                    byte[] byt = new byte[input.available()];
                    input.read(byt);
                    return byt;
                }
            }
        }
    }

    private String getUserAddClasspath()
    {
        return userJars.stream()
                .map(URL::getPath)
                .collect(Collectors.joining(File.pathSeparator));
    }

    private List<String> buildMainArg(int port)
    {
        File java = new File(new File(System.getProperty("java.home"), "bin"), "java");
        ArrayList<String> ops = new ArrayList<>();
        ops.add(java.toString());
        ops.add("-classpath");
        //ops.add(System.getProperty("java.class.path"));
        String userSdkJars = getUserAddClasspath(); //编译时还需要 用户的额外jar依赖
        ops.add(System.getProperty("java.class.path") + ":" + userSdkJars);

        String javaLibPath = System.getProperty("java.library.path");
        if (javaLibPath != null) {
            ops.add("-Djava.library.path=" + javaLibPath);
        }
        ops.add(JVMLauncher.class.getCanonicalName()); //子进程会启动这个类 进行编译
        ops.add(Integer.toString(port));
        return ops;
    }

    public static void main(String[] args)
            throws Exception
    {
        System.out.println("vm start ok ...");
        VmFuture<? extends Serializable> future;

        try (ObjectInputStreamProxy ois = new ObjectInputStreamProxy(System.in)) {
            VmCallable<? extends Serializable> callable = (VmCallable<? extends Serializable>) ois.readObject();
            System.out.println("vm start init ok ...");
            future = new VmFuture<>(callable.call());
        }
        catch (Throwable e) {
            future = new VmFuture<>(Throwables.getStackTraceAsString(e));
        }

        try (OutputStream out = chooseOutputStream(args)) {
            out.write(Serializables.serialize(future));
            System.out.println("vm exiting ok ...");
        }
    }

    private static OutputStream chooseOutputStream(String[] args)
            throws IOException
    {
        if (args.length > 0) {
            int port = Integer.parseInt(args[0]);
            Socket sock = new Socket();
            sock.connect(new InetSocketAddress(InetAddress.getLocalHost(), port));
            return sock.getOutputStream();
        }
        else {
            return System.out;
        }
    }
}
