package net.roguelogix.phosphophyllite.items;

import net.minecraft.world.item.Item;
import net.roguelogix.phosphophyllite.registry.RegisterItem;

@RegisterItem(name = "debug_tool")
public class DebugTool extends Item {
    
    @RegisterItem.Instance
    public static DebugTool INSTANCE;
    
    public DebugTool(Properties properties) {
        super(properties.stacksTo(1));
    }
}
