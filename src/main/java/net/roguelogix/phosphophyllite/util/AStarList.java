package net.roguelogix.phosphophyllite.util;


import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;
import java.util.TreeSet;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class AStarList {
    
    TreeSet<BlockPos> targets = new TreeSet<>(this::orderingFunction);
    TreeSet<BlockPos> nodeSet = new TreeSet<>(this::orderingFunction);
    
    private int orderingFunction(BlockPos a, BlockPos b) {
        if(targets.isEmpty()){
            return 0;
        }
        BlockPos target = targets.first();
        double aDistance = a.distSqr(target);
        double bDistance = b.distSqr(target);
        int aHash = a.hashCode();
        int bHash = b.hashCode();
        return aDistance < bDistance ? -1 : (bDistance == aDistance ? Integer.compare(aHash, bHash) : 1);
    }
    
    public void addTarget(BlockPos target) {
        if (targets.isEmpty()) {
            nodeSet.add(target);
        }
        targets.add(target);
    }
    
    public void addNode(BlockPos node) {
        targets.remove(node);
        nodeSet.add(node);
    }
    
    public BlockPos nextNode() {
        return Objects.requireNonNull(nodeSet.pollFirst());
    }
    
    public boolean done() {
        return targets.isEmpty() || nodeSet.isEmpty();
    }
    
    public boolean foundAll() {
        return targets.isEmpty();
    }
}
