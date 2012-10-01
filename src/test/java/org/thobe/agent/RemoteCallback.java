package org.thobe.agent;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface RemoteCallback extends Remote
{
    void invoke( String inst, String unsafe ) throws RemoteException;
}
