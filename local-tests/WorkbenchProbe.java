import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import com.sun.tools.attach.VirtualMachine;

/** Actual client model comparison; no persistent mod, JVM option, or world mutation. */
public class WorkbenchProbe {
    static Instrumentation instrumentation;
    static Class<?> type(String name) throws Exception {
        for (Class<?> c:instrumentation.getAllLoadedClasses()) if(c.getName().equals(name)) return c;
        throw new ClassNotFoundException(name);
    }
    static Object field(Object target,String name) throws Exception {
        for(Class<?> c=target instanceof Class?(Class<?>)target:target.getClass();c!=null;c=c.getSuperclass()) {
            try {Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(target instanceof Class?null:target);}
            catch(NoSuchFieldException ignored){}
        }
        throw new NoSuchFieldException(name);
    }
    static String mesh(Object model,Object state,ClassLoader loader) throws Exception {
        Class<?> stateType=type("net.minecraft.world.level.block.state.BlockState"),directionType=type("net.minecraft.core.Direction"),randomType=type("net.minecraft.util.RandomSource");
        Method quads=type("net.minecraft.client.resources.model.BakedModel").getMethod("getQuads",stateType,directionType,randomType);
        Method create=randomType.getMethod("create",long.class),vertices=Class.forName("net.minecraft.client.renderer.block.model.BakedQuad",true,loader).getMethod("getVertices");
        Class<?> quadType=vertices.getDeclaringClass();
        Method getSprite=quadType.getMethod("getSprite"),getTint=quadType.getMethod("getTintIndex"),getShade=quadType.getMethod("isShade");
        Class<?> spriteType=type("net.minecraft.client.renderer.texture.TextureAtlasSprite");
        Method u0=spriteType.getMethod("getU0"),u1=spriteType.getMethod("getU1"),v0=spriteType.getMethod("getV0"),v1=spriteType.getMethod("getV1"),contents=spriteType.getMethod("contents");
        Method spriteName=type("net.minecraft.client.renderer.texture.SpriteContents").getMethod("name");
        List<Object> faces=new ArrayList<>();faces.add(null);faces.addAll(Arrays.asList(directionType.getEnumConstants()));
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        MessageDigest normalized=MessageDigest.getInstance("SHA-256");
        for(long seed:new long[]{0,42})for(Object face:faces) {
            List<?> values=(List<?>)quads.invoke(model,state,face,create.invoke(null,seed));
            for(Object q:values) {
                Object sprite=getSprite.invoke(q);
                normalized.update((spriteName.invoke(contents.invoke(sprite))+":"+getTint.invoke(q)+":"+getShade.invoke(q)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                float minU=(float)u0.invoke(sprite),maxU=(float)u1.invoke(sprite),minV=(float)v0.invoke(sprite),maxV=(float)v1.invoke(sprite);
                int[] data=(int[])vertices.invoke(q);
                if(data.length%8!=0)throw new IllegalStateException("Unexpected quad vertex stride");
                for(int i=0;i<data.length;i++) {
                    int raw=data[i],stable=raw;
                    if(i%8==4)stable=Math.round((Float.intBitsToFloat(raw)-minU)/(maxU-minU)*4096);
                    else if(i%8==5)stable=Math.round((Float.intBitsToFloat(raw)-minV)/(maxV-minV)*4096);
                    for(int shift=0;shift<32;shift+=8){digest.update((byte)(raw>>>shift));normalized.update((byte)(stable>>>shift));}
                }
            }
            digest.update((byte)values.size());
            normalized.update((byte)values.size());
        }
        return HexFormat.of().formatHex(digest.digest())+"\t"+HexFormat.of().formatHex(normalized.digest());
    }
    public static void agentmain(String args,Instrumentation inst) throws Exception {
        instrumentation=inst;String[] parts=args.split("\\|",2);Path output=Path.of(parts[0]);String action=parts.length>1?parts[1]:"models";
        Class<?> mcType=type("net.minecraft.client.Minecraft");Object mc=mcType.getMethod("getInstance").invoke(null);ClassLoader loader=mcType.getClassLoader();CompletableFuture<Void> done=new CompletableFuture<>();
        mcType.getMethod("execute",Runnable.class).invoke(mc,(Runnable)()->{
            StringBuilder text=new StringBuilder();
            try {
                if(action.equals("stop")) {
                    if(field(mc,"level")!=null)throw new IllegalStateException("Refusing to close a loaded world");
                    text.append("closed_from="+field(mc,"screen").getClass().getName()+"\n");
                    mcType.getMethod("stop").invoke(mc);
                } else {
                    Map<?,?> conversions=(Map<?,?>)type("fuzs.visualworkbench.handler.BlockConversionHandler").getMethod("getBlockConversions").invoke(null);
                    Object manager=mcType.getMethod("getModelManager").invoke(mc),missing=manager.getClass().getMethod("getMissingModel").invoke(manager);
                    Method modelId=Class.forName("net.minecraft.client.renderer.block.BlockModelShaper",true,loader).getMethod("stateToModelLocation",type("net.minecraft.world.level.block.state.BlockState"));
                    Method getModel=manager.getClass().getMethod("getModel",type("net.minecraft.client.resources.model.ModelResourceLocation"));
                    Method stateDefinition=type("net.minecraft.world.level.block.Block").getMethod("getStateDefinition");
                    Method allStates=type("net.minecraft.world.level.block.state.StateDefinition").getMethod("getPossibleStates");
                    text.append("original\treplacement\tstate\tmissing\tmodel_class\tmesh_sha256\tnormalized_material_mesh_sha256\n");
                    int count=0;
                    for(var entry:conversions.entrySet()) {
                        for(Object state:(Collection<?>)allStates.invoke(stateDefinition.invoke(entry.getValue()))) {
                            Object model=getModel.invoke(manager,modelId.invoke(null,state));
                            text.append(entry.getKey()+"\t"+entry.getValue()+"\t"+state+"\t"+(model==missing)+"\t"+model.getClass().getName()+"\t"+mesh(model,state,loader)+"\n");count++;
                        }
                    }
                    text.append("SUMMARY\tblocks="+conversions.size()+"\tstates="+count+"\n");
                }
            } catch(Throwable e){text.append("ERROR="+e+"\n");for(StackTraceElement x:e.getStackTrace())text.append(x+"\n");if(e.getCause()!=null)text.append("CAUSE="+e.getCause()+"\n");}
            try{Files.writeString(output,text.toString());done.complete(null);}catch(Exception e){done.completeExceptionally(e);}
        });
        done.get(60,TimeUnit.SECONDS);
    }
    public static void main(String[] args)throws Exception {
        VirtualMachine vm=VirtualMachine.attach(args[0]);try{vm.loadAgent(args[1],args[2]);}finally{vm.detach();}
    }
}
