package com.neurocenter.api.plugin;
import java.lang.reflect.*; import java.security.*; import java.util.Map; import java.util.concurrent.ConcurrentHashMap;
@SuppressWarnings("removal") public final class ServiceRegistry {
 private final Map<Class<?>,Object> providers=new ConcurrentHashMap<>(), proxies=new ConcurrentHashMap<>();
 public <T> void register(Class<T> c,T impl){if(c==null||!c.isInterface()||impl==null||!c.isInstance(impl))throw new IllegalArgumentException("Contrato o implementación no válidos.");providers.put(c,impl);proxies.remove(c);}
 public void unregister(Class<?> c){providers.remove(c);proxies.remove(c);} public boolean contains(Class<?> c){return providers.containsKey(c);}
 public <T>T get(Class<T> c){checkAccess(c);if(!providers.containsKey(c))return null;Object p=proxies.computeIfAbsent(c,k->Proxy.newProxyInstance(k.getClassLoader(),new Class<?>[]{k},(proxy,m,args)->{checkAccess(k);try{return AccessController.doPrivileged((PrivilegedAction<Object>)()->{try{return m.invoke(providers.get(k),args);}catch(InvocationTargetException e){throw new Wrapped(e.getCause());}catch(ReflectiveOperationException e){throw new Wrapped(e);}});}catch(Wrapped e){throw e.getCause();}}));return c.cast(p);}
 private static void checkAccess(Class<?> contract) {
  String name = contract.getName();
  if ("com.neurocenter.api.services.AgendaBridgeService".equals(name))
   AccessController.checkPermission(new RuntimePermission("neurocenter.agenda.write"));
  if ("com.neurocenter.api.services.AgendaService".equals(name))
   AccessController.checkPermission(new RuntimePermission("neurocenter.internalAgendaService"));
 }
 private static final class Wrapped extends RuntimeException{Wrapped(Throwable t){super(t);}}
}
