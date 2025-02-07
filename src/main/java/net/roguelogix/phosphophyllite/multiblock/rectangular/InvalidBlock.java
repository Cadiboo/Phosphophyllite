package net.roguelogix.phosphophyllite.multiblock.rectangular;

import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.world.level.block.Block;
import net.roguelogix.phosphophyllite.multiblock.generic.ValidationError;
import net.roguelogix.phosphophyllite.repack.org.joml.Vector3i;

@SuppressWarnings("unused")
public class InvalidBlock extends ValidationError {
    
    public InvalidBlock() {
        super();
    }
    
    public InvalidBlock(String s) {
        super(s);
    }
    
    public InvalidBlock(String message, Throwable cause) {
        super(message, cause);
    }
    
    public InvalidBlock(Throwable cause) {
        super(cause);
    }
    
    public InvalidBlock(Block block, Vector3i worldPosition, String multiblockPosition) {
        super(new TranslatableComponent(
                "multiblock.error.phosphophyllite.invalid_block." + multiblockPosition,
                new TranslatableComponent(block.getDescriptionId()),
                "(x: " + worldPosition.x + "; y: " + worldPosition.y + "; z: " + worldPosition.z + ")")
        );
    }
}
