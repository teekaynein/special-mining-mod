package de.teekay.specialmining;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Special Mining: Spitzhacke/Schaufel mit dieser Verzauberung baut 3x3 ab.
 * Schleichen = nur der eine Block.
 */
public class SpecialMining implements ModInitializer {
	public static final String MOD_ID = "specialmining";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final ResourceKey<Enchantment> SPECIAL_MINING = ResourceKey.create(
			Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(MOD_ID, "special_mining"));

	/** Welche Seite des Blocks der Spieler beim Abbauen angeschaut hat. */
	private static final Map<UUID, Direction> PENDING_FACE = new HashMap<>();
	/** Verhindert, dass die Zusatz-Blöcke selbst wieder 3x3 auslösen. */
	private static final Set<UUID> CURRENTLY_BREAKING = new HashSet<>();

	@Override
	public void onInitialize() {
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return true;
			}
			UUID id = serverPlayer.getUUID();
			PENDING_FACE.remove(id);

			if (CURRENTLY_BREAKING.contains(id) || serverPlayer.isShiftKeyDown()) {
				return true;
			}
			ItemStack tool = serverPlayer.getMainHandItem();
			if (!hasSpecialMining(tool) || !tool.isCorrectToolForDrops(state)) {
				return true;
			}

			// Block ist hier noch da -> Blickrichtung per Raycast bestimmen
			HitResult hit = serverPlayer.pick(serverPlayer.blockInteractionRange() + 1.0D, 1.0F, false);
			Direction face;
			if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
				face = blockHit.getDirection();
			} else {
				face = Direction.orderedByNearest(serverPlayer)[0].getOpposite();
			}
			PENDING_FACE.put(id, face);
			return true;
		});

		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return;
			}
			UUID id = serverPlayer.getUUID();
			Direction face = PENDING_FACE.remove(id);
			if (face == null || CURRENTLY_BREAKING.contains(id)) {
				return;
			}
			breakArea(level, serverPlayer, pos, face);
		});

		LOGGER.info("Special Mining geladen!");
	}

	private static void breakArea(Level level, ServerPlayer player, BlockPos center, Direction face) {
		UUID id = player.getUUID();
		CURRENTLY_BREAKING.add(id);
		try {
			for (int a = -1; a <= 1; a++) {
				for (int b = -1; b <= 1; b++) {
					if (a == 0 && b == 0) {
						continue;
					}
					ItemStack tool = player.getMainHandItem();
					// Werkzeug kaputt oder gewechselt -> aufhören
					if (tool.isEmpty() || !hasSpecialMining(tool)) {
						return;
					}

					BlockPos target = offset(center, face.getAxis(), a, b);
					BlockState targetState = level.getBlockState(target);
					if (targetState.isAir()) {
						continue;
					}
					// Grundgestein & Co. (unzerstörbar) überspringen
					if (targetState.getDestroySpeed(level, target) < 0) {
						continue;
					}
					// Nur Blöcke, für die das Werkzeug passt (Spitzhacke: Stein/Erze, Schaufel: Erde/Sand/Kies)
					if (!tool.isCorrectToolForDrops(targetState)) {
						continue;
					}
					if (!level.mayInteract(player, target)) {
						continue;
					}
					// Letzter Haltbarkeitspunkt: keine Zusatz-Blöcke, damit das Werkzeug nicht kaputtgeht
					if (tool.isDamageableItem() && tool.getMaxDamage() - tool.getDamageValue() <= 1) {
						return;
					}
					// Normaler Abbau: Drops, Glück, Behutsamkeit, Schutzgebiete funktionieren wie immer.
					// Zusatz-Blöcke kosten aber KEINE Haltbarkeit -> nur 1 pro Schlag.
					int damageBefore = tool.getDamageValue();
					player.gameMode.destroyBlock(target);
					if (!tool.isEmpty() && tool.isDamageableItem()) {
						tool.setDamageValue(damageBefore);
					}
				}
			}
		} finally {
			CURRENTLY_BREAKING.remove(id);
		}
	}

	private static BlockPos offset(BlockPos center, Direction.Axis axis, int a, int b) {
		return switch (axis) {
			case X -> center.offset(0, a, b);
			case Y -> center.offset(a, 0, b);
			case Z -> center.offset(a, b, 0);
		};
	}

	public static boolean hasSpecialMining(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		for (Holder<Enchantment> enchantment : stack.getEnchantments().keySet()) {
			if (enchantment.is(SPECIAL_MINING)) {
				return true;
			}
		}
		return false;
	}
}
