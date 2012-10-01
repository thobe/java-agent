package org.thobe.agent;

import java.io.Serializable;
import java.lang.instrument.Instrumentation;
import java.rmi.Remote;
import java.rmi.RemoteException;

import com.sun.tools.attach.VirtualMachineDescriptor;
import sun.misc.Unsafe;

public interface CallbackInvoker<CALLBACK extends Remote> extends Serializable
{
    void invokeCallback( CALLBACK callback, Instrumentation instrumentation, Unsafe unsafe ) throws RemoteException;

    void attachFailed( CALLBACK callback, VirtualMachineDescriptor descriptor, Throwable failure ) throws RemoteException;
}
