package net.roguelogix.phosphophyllite.multiblock.generic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.roguelogix.phosphophyllite.Phosphophyllite;
import net.roguelogix.phosphophyllite.repack.org.joml.Vector2i;
import net.roguelogix.phosphophyllite.repack.org.joml.Vector3i;
import net.roguelogix.phosphophyllite.repack.org.joml.Vector3ic;
import net.roguelogix.phosphophyllite.util.AStarList;
import net.roguelogix.phosphophyllite.util.TileMap;
import net.roguelogix.phosphophyllite.util.Util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

public class MultiblockController<ControllerType extends MultiblockController<ControllerType, TileType, BlockType>, TileType extends MultiblockTile<ControllerType, TileType, BlockType>, BlockType extends MultiblockBlock<ControllerType, TileType, BlockType>> {
    
    protected final Level world;
    
    private boolean hasSaveDelegate = false;
    protected final TileMap<TileType> blocks = new TileMap<>();
    protected final Set<ITickableMultiblockTile> toTick = new LinkedHashSet<>();
    protected final Set<IAssemblyAttemptedTile> assemblyAttemptedTiles = new LinkedHashSet<>();
    protected final Set<IOnAssemblyTile> onAssemblyTiles = new LinkedHashSet<>();
    protected final Set<IOnDisassemblyTile> onDisassemblyTiles = new LinkedHashSet<>();
    private boolean checkForDetachments = false;
    private boolean updateExtremes = true;
    private long updateAssemblyAtTick = Long.MAX_VALUE;
    protected final Set<ControllerType> controllersToMerge = new LinkedHashSet<>();
    protected final List<BlockPos> removedBlocks = new LinkedList<>();
    
    private final Vector3i minCoord = new Vector3i();
    private final Vector3i maxCoord = new Vector3i();
    private final Vector3i minExtremeBlocks = new Vector3i();
    private final Vector3i maxExtremeBlocks = new Vector3i();
    
    
    public enum AssemblyState {
        ASSEMBLED,
        DISASSEMBLED,
        PAUSED,
    }
    
    protected AssemblyState state = AssemblyState.DISASSEMBLED;
    
    private boolean shouldUpdateNBT = false;
    private CompoundTag cachedNBT = null;
    
    protected final Validator<MultiblockTile<?, ?, ?>> tileTypeValidator;
    protected final Validator<MultiblockBlock<?, ?, ?>> blockTypeValidator;
    private Validator<ControllerType> assemblyValidator = c -> true;
    
    protected ValidationError lastValidationError = null;
    
    long lastTick = -1;
    
    
    public MultiblockController(@Nonnull Level world, @Nonnull Validator<MultiblockTile<?, ?, ?>> tileTypeValidator, @Nonnull Validator<MultiblockBlock<?, ?, ?>> blockTypeValidator) {
        this.tileTypeValidator = tileTypeValidator;
        this.blockTypeValidator = blockTypeValidator;
        this.world = world;
        Phosphophyllite.addController(this);
    }
    
    ControllerType self() {
        //noinspection unchecked
        return (ControllerType) this;
    }
    
    public Level getWorld() {
        return world;
    }
    
    public Vector3ic minCoord() {
        return minCoord;
    }
    
    public Vector3ic maxCoord() {
        return maxCoord;
    }
    
    @Nullable
    public TileType getTile(Vector3i position) {
        return blocks.getTile(position);
    }
    
    @Nullable
    public TileType getTile(BlockPos position) {
        return blocks.getTile(position);
    }
    
    public boolean containsTile(TileType tile) {
        return blocks.containsTile(tile);
    }
    
    private void updateMinMaxCoordinates() {
        if (blocks.isEmpty() || !updateExtremes) {
            return;
        }
        updateExtremes = false;
        minCoord.set(Integer.MAX_VALUE);
        maxCoord.set(Integer.MIN_VALUE);
        blocks.forEachPos(pos -> {
            if (pos.getX() < minCoord.x) {
                minCoord.x = pos.getX();
                minExtremeBlocks.x = 1;
            } else if (pos.getX() == minCoord.x) {
                minExtremeBlocks.x++;
            }
            if (pos.getY() < minCoord.y) {
                minCoord.y = pos.getY();
                minExtremeBlocks.y = 1;
            } else if (pos.getY() == minCoord.y) {
                minExtremeBlocks.y++;
            }
            if (pos.getZ() < minCoord.z) {
                minCoord.z = pos.getZ();
                minExtremeBlocks.z = 1;
            } else if (pos.getZ() == minCoord.z) {
                minExtremeBlocks.z++;
            }
            if (pos.getX() > maxCoord.x) {
                maxCoord.x = pos.getX();
                maxExtremeBlocks.x = 1;
            } else if (pos.getX() == maxCoord.x) {
                maxExtremeBlocks.x++;
            }
            if (pos.getY() > maxCoord.y) {
                maxCoord.y = pos.getY();
                maxExtremeBlocks.y = 1;
            } else if (pos.getY() == maxCoord.y) {
                maxExtremeBlocks.y++;
                
            }
            if (pos.getZ() > maxCoord.z) {
                maxCoord.z = pos.getZ();
                maxExtremeBlocks.z = 1;
            } else if (pos.getZ() == maxCoord.z) {
                maxExtremeBlocks.z++;
            }
        });
    }
    
    final void attemptAttach(@Nonnull MultiblockTile<?, ?, ?> toAttachGeneric) {
        
        if (!tileTypeValidator.validate(toAttachGeneric)) {
            return;
        }
        
        TileType toAttach;
        try {
            // i check directly above
            //noinspection unchecked
            toAttach = (TileType) toAttachGeneric;
        } catch (ClassCastException ignored) {
            // you implemented that check wrong
            Phosphophyllite.LOGGER.error("Tile type validator implemented wrong! Cleared tile that couldn't be cast. " + toAttachGeneric.getClass().toGenericString());
            return;
        }
        
        if (toAttach.controller != null && toAttach.controller != this) {
            if (toAttach.controller.blocks.size() > blocks.size()) {
                toAttach.controller.controllersToMerge.add(self());
            } else {
                controllersToMerge.add(toAttach.controller);
            }
            return;
        }
        
        // check if already attached
        if (toAttach.controller == this) {
            return;
        }
        
        // ok, its a valid tile to attach, so ima attach it
        blocks.addTile(toAttach);
        
        BlockPos toAttachPos = toAttach.getBlockPos();
        // update minmax
        if (toAttachPos.getX() < minCoord.x) {
            minCoord.x = toAttachPos.getX();
            minExtremeBlocks.x = 1;
        } else if (toAttachPos.getX() == minCoord.x) {
            minExtremeBlocks.x++;
        }
        if (toAttachPos.getY() < minCoord.y) {
            minCoord.y = toAttachPos.getY();
            minExtremeBlocks.y = 1;
        } else if (toAttachPos.getY() == minCoord.y) {
            minExtremeBlocks.y++;
        }
        if (toAttachPos.getZ() < minCoord.z) {
            minCoord.z = toAttachPos.getZ();
            minExtremeBlocks.z = 1;
        } else if (toAttachPos.getZ() == minCoord.z) {
            minExtremeBlocks.z++;
        }
        if (toAttachPos.getX() > maxCoord.x) {
            maxCoord.x = toAttachPos.getX();
            maxExtremeBlocks.x = 1;
        } else if (toAttachPos.getX() == maxCoord.x) {
            maxExtremeBlocks.x++;
        }
        if (toAttachPos.getY() > maxCoord.y) {
            maxCoord.y = toAttachPos.getY();
            maxExtremeBlocks.y = 1;
        } else if (toAttachPos.getY() == maxCoord.y) {
            maxExtremeBlocks.y++;
        }
        if (toAttachPos.getZ() > maxCoord.z) {
            maxCoord.z = toAttachPos.getZ();
            maxExtremeBlocks.z = 1;
        } else if (toAttachPos.getZ() == maxCoord.z) {
            maxExtremeBlocks.z++;
        }
        
        if (toAttach instanceof ITickableMultiblockTile) {
            toTick.add((ITickableMultiblockTile) toAttach);
        }
        if (toAttach instanceof IAssemblyAttemptedTile) {
            assemblyAttemptedTiles.add((IAssemblyAttemptedTile) toAttach);
        }
        if (toAttach instanceof IOnAssemblyTile) {
            onAssemblyTiles.add((IOnAssemblyTile) toAttach);
        }
        if (toAttach instanceof IOnDisassemblyTile) {
            onDisassemblyTiles.add((IOnDisassemblyTile) toAttach);
        }
        
        if (toAttach.isSaveDelegate) {
            if (hasSaveDelegate) {
                toAttach.isSaveDelegate = false;
            } else {
                hasSaveDelegate = true;
            }
        }
        
        toAttach.controller = self();
        if (toAttach.preExistingBlock) {
            if (toAttach.controllerData != null) {
                onBlockWithNBTAttached(toAttach.controllerData);
                toAttach.controllerData = null;
            }
            onPartAttached(toAttach);
        } else {
            onPartPlaced(toAttach);
        }
        updateAssemblyAtTick = Phosphophyllite.tickNumber() + 1;
    }
    
    final void detach(@Nonnull TileType toDetach) {
        detach(toDetach, false);
    }
    
    final void detach(@Nonnull TileType toDetach, boolean onChunkUnload) {
        detach(toDetach, onChunkUnload, true);
    }
    
    final void detach(@Nonnull TileType toDetach, boolean onChunkUnload, boolean checkForDetachments) {
        blocks.removeTile(toDetach);
        if (toDetach instanceof ITickableMultiblockTile) {
            toTick.remove(toDetach);
        }
        if (toDetach instanceof IAssemblyAttemptedTile) {
            assemblyAttemptedTiles.remove(toDetach);
        }
        if (toDetach instanceof IOnAssemblyTile) {
            onAssemblyTiles.remove(toDetach);
        }
        if (toDetach instanceof IOnDisassemblyTile) {
            onDisassemblyTiles.remove(toDetach);
        }
        
        if (onChunkUnload) {
            onPartDetached(toDetach);
            state = AssemblyState.PAUSED;
        } else {
            onPartBroken(toDetach);
            // dont need to try to attach if the chunk just unloaded
            toDetach.attemptAttach();
        }
        
        if(toDetach.isSaveDelegate){
            hasSaveDelegate = false;
        }
        
        if (blocks.isEmpty()) {
            Phosphophyllite.removeController(this);
        }
        
        this.checkForDetachments = this.checkForDetachments || checkForDetachments;
        if (checkForDetachments) {
            removedBlocks.add(toDetach.getBlockPos());
        }
        
        BlockPos toDetachPos = toDetach.getBlockPos();
        if (toDetachPos.getX() == minCoord.x) {
            minExtremeBlocks.x--;
            if (minExtremeBlocks.x == 0) {
                updateExtremes = true;
            }
        }
        if (toDetachPos.getY() == minCoord.y) {
            minExtremeBlocks.y--;
            if (minExtremeBlocks.y == 0) {
                updateExtremes = true;
            }
        }
        if (toDetachPos.getZ() == minCoord.z) {
            minExtremeBlocks.z--;
            if (minExtremeBlocks.z == 0) {
                updateExtremes = true;
            }
        }
        if (toDetachPos.getX() == maxCoord.x) {
            maxExtremeBlocks.x--;
            if (maxExtremeBlocks.x == 0) {
                updateExtremes = true;
            }
        }
        if (toDetachPos.getY() == maxCoord.y) {
            maxExtremeBlocks.y--;
            if (maxExtremeBlocks.y == 0) {
                updateExtremes = true;
            }
        }
        if (toDetachPos.getZ() == maxCoord.z) {
            maxExtremeBlocks.z--;
            if (maxExtremeBlocks.z == 0) {
                updateExtremes = true;
            }
        }
        
        updateAssemblyAtTick = Phosphophyllite.tickNumber() + 1;
    }
    
    public final void update() {
        if (lastTick >= Phosphophyllite.tickNumber()) {
            return;
        }
        lastTick = Phosphophyllite.tickNumber();
        
        if (blocks.isEmpty()) {
            // why are we being ticked?
            Phosphophyllite.removeController(this);
            checkForDetachments = false;
        }
        
        if (checkForDetachments) {
            BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
            
            AStarList aStarList = new AStarList();
            
            for (BlockPos removedBlock : removedBlocks) {
                for (Direction value : Direction.values()) {
                    mutableBlockPos.set(removedBlock);
                    mutableBlockPos.move(value);
                    TileType tile = blocks.getTile(mutableBlockPos);
                    if (tile != null && tile.controller == this) {
                        aStarList.addTarget(tile.getBlockPos());
                    }
                }
            }
            removedBlocks.clear();
            
            while (!aStarList.done()) {
                BlockPos node = aStarList.nextNode();
                for (Direction value : Direction.values()) {
                    mutableBlockPos.set(node);
                    mutableBlockPos.move(value);
                    TileType tile = blocks.getTile(mutableBlockPos);
                    if (tile != null && tile.controller == this && tile.lastSavedTick != this.lastTick) {
                        tile.lastSavedTick = this.lastTick;
                        aStarList.addNode(tile.getBlockPos());
                    }
                }
            }
            
            if (!aStarList.foundAll()) {
                HashSet<TileType> toOrphan = new LinkedHashSet<>();
                blocks.forEachTile(tile -> {
                    if (tile.lastSavedTick != this.lastTick) {
                        toOrphan.add(tile);
                    }
                });
                if (!toOrphan.isEmpty()) {
                    for (TileType tile : toOrphan) {
                        detach(tile, state == AssemblyState.PAUSED, false);
                    }
                    updateAssemblyAtTick = Long.MIN_VALUE;
                }
            }
            checkForDetachments = false;
        }
        if (!controllersToMerge.isEmpty()) {
            HashSet<ControllerType> newToMerge = new HashSet<>();
            for (ControllerType otherController : controllersToMerge) {
                Phosphophyllite.removeController(otherController);
                otherController.controllersToMerge.remove(self());
                newToMerge.addAll(otherController.controllersToMerge);
                otherController.controllersToMerge.clear();
                this.onMerge(otherController);
                otherController.blocks.forEachTile(tile -> {
                    tile.controller = null;
                    tile.preExistingBlock = false;
                    attemptAttach(tile);
                });
            }
            updateExtremes = true;
            updateAssemblyAtTick = Long.MIN_VALUE;
            controllersToMerge.clear();
            controllersToMerge.addAll(newToMerge);
        }
        
        if (updateAssemblyAtTick < lastTick) {
            updateMinMaxCoordinates();
            updateAssemblyState();
            updateAssemblyAtTick = Long.MAX_VALUE;
        }
        
        if (state == AssemblyState.ASSEMBLED) {
            tick();
            toTick.forEach(ITickableMultiblockTile::tick);
        } else if (state == AssemblyState.DISASSEMBLED) {
            disassembledTick();
        }
    }
    
    public void suicide() {
        TileMap<TileType> blocks = new TileMap<>();
        blocks.addAll(this.blocks);
        blocks.forEachTile(MultiblockTile::onChunkUnloaded);
        Phosphophyllite.removeController(this);
    }
    
    private void updateAssemblyState() {
        AssemblyState oldState = state;
        boolean validated = false;
        lastValidationError = null;
        try {
            validated = assemblyValidator.validate(self());
        } catch (ValidationError e) {
            lastValidationError = e;
        }
        if (validated) {
            state = AssemblyState.ASSEMBLED;
            if (cachedNBT != null) {
                read(cachedNBT.getCompound("userdata"));
                shouldUpdateNBT = true;
            }
//            blocks.forEach((pos, block) -> world.notifyNeighborsOfStateChange(pos, block.getBlockState().getBlock()));
            if (oldState == AssemblyState.PAUSED) {
                onUnpaused();
            } else {
                onAssembled();
            }
            assembledBlockStates();
            onAssemblyTiles.forEach(IOnAssemblyTile::onAssembly);
            if(!hasSaveDelegate){
                TileType tile = blocks.getOne();
                assert tile != null;
                tile.isSaveDelegate = true;
                hasSaveDelegate = true;
            }
        } else {
            if (oldState == AssemblyState.ASSEMBLED) {
                state = AssemblyState.DISASSEMBLED;
                onDisassembled();
                disassembledBlockStates();
                updateCachedNBT();
                onDisassemblyTiles.forEach(IOnDisassemblyTile::onDisassembly);
            }
        }
        for (IAssemblyAttemptedTile assemblyAttemptedTile : assemblyAttemptedTiles) {
            assemblyAttemptedTile.onAssemblyAttempted();
        }
    }
    
    
    private void onBlockWithNBTAttached(CompoundTag nbt) {
        if (cachedNBT == null) {
            readNBT(nbt);
        }
    }
    
    private void assembledBlockStates() {
        final HashMap<BlockPos, BlockState> newStates = new LinkedHashMap<>();
        blocks.forEach((pos, tile) -> {
            BlockState state = tile.assembledBlockState();
            if (state != tile.getBlockState()) {
                newStates.put(pos, state);
                tile.setBlockState(state);
            }
        });
        Util.setBlockStates(newStates, world);
    }
    
    private void disassembledBlockStates() {
        final HashMap<BlockPos, BlockState> newStates = new LinkedHashMap<>();
        blocks.forEach((pos, tile) -> {
            BlockState state = tile.disassembledBlockState();
            if (state != tile.getBlockState()) {
                newStates.put(pos, state);
                tile.setBlockState(state);
                ;
            }
        });
        Util.setBlockStates(newStates, world);
    }
    
    /**
     * Read from the NBT saved by member blocks
     *
     * @param nbt previously returned by getNBT that represents this multiblock
     */
    final void readNBT(CompoundTag nbt) {
        if (!nbt.isEmpty()) {
            cachedNBT = nbt.copy();
            CompoundTag multiblockData = cachedNBT.getCompound("multiblockData");
            if (multiblockData.contains("assemblyState")) {
                // dont just shove this into this.state
                // if you do onDisassembled will be called incorrectly
                AssemblyState nbtState = AssemblyState.valueOf(multiblockData.getString("assemblyState"));
                // because minecraft is dumb, and saves chunks before unloading them, i need to treat assembled as paused too
                if (state == AssemblyState.DISASSEMBLED && (nbtState == AssemblyState.PAUSED || nbtState == AssemblyState.ASSEMBLED)) {
                    state = AssemblyState.PAUSED;
                }
            }
        }
    }
    
    /**
     * Called by member blocks when saving to world
     * may not directly call write() from here
     * <p>
     * DO NOT EDIT THE RETURNED NBT, weird things can happen if you do
     *
     * @return NBT that represents this multiblock
     */
    @Nonnull
    final CompoundTag getNBT() {
        if (shouldUpdateNBT) {
            shouldUpdateNBT = false;
            updateCachedNBT();
        }
        return cachedNBT == null ? new CompoundTag() : cachedNBT;
    }
    
    private void updateCachedNBT() {
        cachedNBT = new CompoundTag();
        cachedNBT.put("userdata", write());
        CompoundTag multiblockData = new CompoundTag();
        cachedNBT.put("multiblockData", multiblockData);
        {
            // instead of storing an exhaustive list of all the blocks we had
            // just save the controller hash, and make sure we have the right number
            multiblockData.putInt("controller", hashCode());
            multiblockData.putString("assemblyState", state.toString());
        }
    }
    
    /**
     * Marks multiblocks structure as dirty to minecraft so it is saved
     */
    protected final void markDirty() {
        shouldUpdateNBT = true;
        Util.markRangeDirty(world, new Vector2i(minCoord.x, minCoord.z), new Vector2i(maxCoord.x, maxCoord.z));
    }
    
    @Nonnull
    public AssemblyState assemblyState() {
        return state;
    }
    
    /**
     * Gets the info printed to chat when block is clicked with the @DebugTool
     * safe to override, just append to the string returned by super.getDebugInfo
     * not my fault if you manage to break it
     *
     * @return string to print
     */
    @Nonnull
    public String getDebugInfo() {
        return "BlockCount: " + blocks.size() + "\n" +
                "Min " + minCoord.toString() + "\n" +
                "Max " + maxCoord.toString() + "\n" +
                "Controller: " + this + "\n" +
                "Last Error: " + (lastValidationError == null ? "N/A" : lastValidationError.getTextComponent().getString()) + "\n" +
                "AssemblyState: " + state + "\n";
    }
    
    
    // -- API --
    
    /**
     * Sets, or removes, the validator to be used to determine if the multiblock is assembled or not
     *
     * @param validator the new Validator, or null to remove
     */
    
    protected void setAssemblyValidator(@Nullable Validator<ControllerType> validator) {
        if (validator != null) {
            assemblyValidator = validator;
        }
    }
    
    
    /**
     * Called at the end of a tick for assembled multiblocks only
     * is not called if the multiblock is dissassembled or paused
     */
    public void tick() {
    }
    
    /**
     * Called at the end of a tick for dissassembled multiblocks only
     * not called if the multiblock is assembled or paused
     */
    public void disassembledTick() {
    }
    
    /**
     * Called when a part is added to the multiblock structure
     * not called in conjunction with onPartPlaced
     * <p>
     * CANNOT ALTER NBT STATE
     *
     * @param toAttach, the part that was added
     */
    protected void onPartAttached(@Nonnull TileType toAttach) {
    }
    
    /**
     * Called when a part is removed to the multiblock structure
     * not called in conjunction with onPartBroken
     * <p>
     * CANNOT ALTER NBT STATE
     *
     * @param toDetach, the part that was removed
     */
    protected void onPartDetached(@Nonnull TileType toDetach) {
    }
    
    /**
     * Called when a new part is added to the world, or a block is merged in from another multiblock
     * <p>
     * not called when a previously placed block is reloaded
     *
     * @param placed the block that was placed
     */
    protected void onPartPlaced(@Nonnull TileType placed) {
    }
    
    /**
     * Called when a part is removed from the world, or a block is detached durring separation
     * <p>
     * not called when a part is unloaded
     *
     * @param broken the block that was broken
     */
    protected void onPartBroken(@Nonnull TileType broken) {
    }
    
    /**
     * Called when two multiblock controllers are merged together
     * <p>
     * this happens with a block connecting the two is placed
     * <p>
     * only called for the controller that will reside over the blocks both controllers control
     * <p>
     * called before blocks have been moved to the primary controller
     *
     * @param otherController the controller to merge into this one
     */
    protected void onMerge(@Nonnull ControllerType otherController) {
    }
    
    /**
     * Called when a multiblock is assembled by a placed block
     * <p>
     * called after @onPartPlaced
     */
    protected void onAssembled() {
    }
    
    /**
     * Called when a multiblock is assembled by a destroyed block
     * <p>
     * called after @onPartBroken, called before @write
     */
    protected void onDisassembled() {
    }
    
    /**
     * Called when a multiblock is to be resumed from a paused state
     * <p>
     * called after @read but before first call to @tick
     */
    protected void onUnpaused() {
    
    }
    
    /**
     * Called after a multiblock has passes assembly validation, and has an NBT to read from
     * <p>
     * may not be called for new multiblocks
     *
     * @param compound the NBT that was written to in the last write call
     */
    protected void read(@Nonnull CompoundTag compound) {
    }
    
    /**
     * Create an NBT tag to be saved and re-read upon multiblock re-assembly
     * <p>
     * Can be called at any time, and multiblock must be able to resume from this NBT regardless of its current state
     *
     * @return the NBT to save
     */
    @Nonnull
    protected CompoundTag write() {
        return new CompoundTag();
    }
    
}
