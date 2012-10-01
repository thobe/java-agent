package org.thobe.agent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.RemoteObject;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import sun.misc.BASE64Decoder;
import sun.misc.BASE64Encoder;
import sun.misc.Unsafe;

import static java.lang.String.format;

public final class CallbackAgent implements Serializable
{
    private final byte[] callbackStub, invoker;
    private final String classpath;
    private transient Remote callback;
    private transient CallbackInvoker callbackInvoker;

    public <CALLBACK extends Remote> CallbackAgent( CALLBACK callback, CallbackInvoker<CALLBACK> invoker )
            throws NoSuchObjectException
    {
        verifyAgentJar( jarFile() );
        Remote stub = RemoteObject.toStub( callback );
        StringBuilder classpath = new StringBuilder();
        for ( Class<?> iFace : stub.getClass().getInterfaces() )
        {
            if ( iFace != Remote.class && Remote.class.isAssignableFrom( iFace ) )
            {
                String codeSource = codeSourceOf( iFace );
                try
                {
                    new JarFile( codeSource );
                }
                catch ( IOException e )
                {
                    codeSource = new JarCreator().createTemporaryJar( codeSource );
                }
                classpath.append( ':' ).append( codeSource );
            }
        }
        this.callbackStub = serialize( stub );
        this.invoker = serialize( invoker );
        this.classpath = classpath.length() == 0 ? "" : classpath.substring( 1 );
        this.callback = callback;
        this.callbackInvoker = invoker;
    }

    public void injectInto( String pid )
    {
        try
        {
            VirtualMachine vm;
            try
            {
                vm = VirtualMachine.attach( pid );
            }
            catch ( AttachNotSupportedException e )
            {
                throw new IllegalArgumentException( "Could not attach to: " + pid, e );
            }
            deployAgent( vm );
            vm.detach();
        }
        catch ( IOException e )
        {
            throw new IllegalStateException( "Could not communicate with process: " + pid, e );
        }
    }

    public void injectIntoAll()
    {
        for ( VirtualMachineDescriptor descriptor : VirtualMachine.list() )
        {
            try
            {
                VirtualMachine vm;
                try
                {
                    vm = VirtualMachine.attach( descriptor );
                }
                catch ( AttachNotSupportedException e )
                {
                    attachFailed( descriptor, e );
                    continue;
                }
                try
                {
                    deployAgent( vm );
                }
                catch ( IllegalStateException e )
                {
                    attachFailed( descriptor, e.getCause() );
                }
                vm.detach();
            }
            catch ( IOException e )
            {
                // ignore
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void attachFailed( VirtualMachineDescriptor descriptor, Throwable failure )
            throws RemoteException
    {
        callbackInvoker.attachFailed( callback, descriptor, failure );
    }

    private void deployAgent( VirtualMachine vm ) throws IOException
    {
        try
        {
            vm.loadAgent( jarFile(), serialized() );
        }
        catch ( AgentLoadException e )
        {
            throw new IllegalStateException( "Could not load agent: " + e.getMessage(), e );
        }
        catch ( AgentInitializationException e )
        {
            throw new IllegalStateException( "Could not initialize agent: " + e.getMessage(), e );
        }
    }

    private static String verifyAgentJar( String jarPath )
    {
        try
        {
            JarFile jarFile = new JarFile( jarPath );
            if ( jarFile.getManifest().getMainAttributes().get( new Attributes.Name( "Agent-Class" ) ) == null )
            {
                throw new IllegalStateException( jarPath + " does not specify an Agent-Class" );
            }
        }
        catch ( IOException e )
        {
            throw new IllegalStateException( jarPath + " does not represent a jar file", e );
        }
        return jarPath;
    }

    private String jarFile()
    {
        String jarFile = codeSourceOf( getClass() );
        try
        {
            verifyAgentJar( jarFile );
        }
        catch ( IllegalStateException e )
        {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put( new Attributes.Name( "Agent-Class" ), getClass().getName() );
            return new JarCreator( manifest ).createTemporaryJar( jarFile );
        }
        return jarFile;
    }

    private static String codeSourceOf( Class<?> type )
    {
        return type.getProtectionDomain().getCodeSource().getLocation().getPath();
    }

    private String serialized()
    {
        return new BASE64Encoder().encode( serialize( this ) );
    }

    @SuppressWarnings("unchecked")
    private void load( final Instrumentation inst ) throws IOException
    {
        for ( String jar : classpath.split( ":" ) )
        {
            inst.appendToSystemClassLoaderSearch( new JarFile( jar ) );
        }
        this.callback = deserialize( Remote.class, callbackStub );
        this.callbackInvoker = deserialize( CallbackInvoker.class, invoker );
        new Thread( format( "%s{%s.invokeCallback(%s)}", getClass().getSimpleName(), callbackInvoker, callback ) )
        {
            @Override
            public void run()
            {
                try
                {
                    callbackInvoker.invokeCallback( callback, inst, getUnsafe() );
                }
                catch ( RemoteException e )
                {
                    e.printStackTrace();
                }
            }
        }.start();
    }

    private static Unsafe getUnsafe()
    {
        try
        {
            return Unsafe.getUnsafe();
        }
        catch ( Throwable accessFailure )
        {
            try
            {
                for ( Field field : Unsafe.class.getDeclaredFields() )
                {
                    if ( Modifier.isStatic( field.getModifiers() ) && field.getType() == Unsafe.class )
                    {
                        field.setAccessible( true );
                        return (Unsafe) field.get( null );
                    }
                }
            }
            catch ( Throwable reflectionFailure )
            {
                // ignore
            }
            return null;
        }
    }

    public static void agentmain( String agentArgs, Instrumentation inst ) throws IOException
    {
        deserialize( CallbackAgent.class, agentArgs ).load( inst );
    }

    private static byte[] serialize( Object obj )
    {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try
        {
            ObjectOutputStream oos = new ObjectOutputStream( os );
            oos.writeObject( obj );
            oos.close();
        }
        catch ( IOException e )
        {
            throw new IllegalStateException( "Could not serialize " + obj, e );
        }
        return os.toByteArray();
    }

    private static <T> T deserialize( Class<T> type, String serialized )
    {
        try
        {
            return deserialize( type, new BASE64Decoder().decodeBuffer( serialized ) );
        }
        catch ( IOException e )
        {
            throw new IllegalArgumentException( "Could not de-serialize " + type.getName(), e );
        }
    }

    private static <T> T deserialize( Class<T> type, byte[] serialized )
    {
        try
        {
            return type.cast( new ObjectInputStream( new ByteArrayInputStream( serialized ) ).readObject() );
        }
        catch ( IOException e )
        {
            throw new IllegalArgumentException( "Could not de-serialize " + type.getName(), e );
        }
        catch ( ClassNotFoundException e )
        {
            throw new IllegalStateException( "Serialized class not on classpath.", e );
        }
    }
}
