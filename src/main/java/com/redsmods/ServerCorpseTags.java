package com.redsmods;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

public class ServerCorpseTags {
    public static final TagKey<Item> REVIVE = TagKey.create(
            Registries.ITEM,
            Identifier.fromNamespaceAndPath("servercorpse", "revive")
    );
}