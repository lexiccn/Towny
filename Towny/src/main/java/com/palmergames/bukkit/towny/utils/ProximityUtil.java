package com.palmergames.bukkit.towny.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;

import com.palmergames.bukkit.towny.TownyMessaging;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.exceptions.TownyException;
import com.palmergames.bukkit.towny.object.Coord;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownyWorld;
import com.palmergames.bukkit.towny.object.Translatable;
import com.palmergames.bukkit.towny.object.WorldCoord;
import com.palmergames.util.MathUtil;

public class ProximityUtil {

	/*
	 * Town Proximity Methods
	 */

	public static void allowTownHomeBlockOrThrow(TownyWorld world, Coord key, @Nullable Town town, boolean newTown) throws TownyException {
		if (!world.hasTowns()) // No towns exist yet, we cannot be too close to any other town.
			return;

		if (newTown) { // Tests run only when there is a new town involved.
			if (TownySettings.getMinDistanceFromTownPlotblocks() > 0 || TownySettings.getNewTownMinDistanceFromTownPlots() > 0) {
				// Sometimes new towns have special min. distances from other towns.
				int minDistance = TownySettings.getNewTownMinDistanceFromTownPlots();
				if (minDistance <= 0)
					minDistance = TownySettings.getMinDistanceFromTownPlotblocks();

				// throws when a new town is being made to close to another town's land.
				if (world.getMinDistanceFromOtherTownsPlots(key) < minDistance)
					throw new TownyException(Translatable.of("msg_too_close2", Translatable.of("townblock")));
			}
		}

		if (TownySettings.getMinDistanceFromTownHomeblocks() > 0 ||
			TownySettings.getMaxDistanceBetweenHomeblocks() > 0 ||
			TownySettings.getMinDistanceBetweenHomeblocks() > 0 ||
			(newTown && TownySettings.getNewTownMinDistanceFromTownHomeblocks() > 0)) {

			final int distanceToNextNearestHomeblock = world.getMinDistanceFromOtherTownsHomeBlocks(key, town);
			// Sometimes new towns have special min. distances from other towns' homeblocks.
			int minDistance = newTown ? TownySettings.getNewTownMinDistanceFromTownHomeblocks() : 0;
			if (minDistance <= 0)
				minDistance = TownySettings.getMinDistanceFromTownHomeblocks();

			// throws when the town's homeblock is too close to another homeblock.
			if (distanceToNextNearestHomeblock < minDistance || distanceToNextNearestHomeblock < TownySettings.getMinDistanceBetweenHomeblocks()) 
				throw new TownyException(Translatable.of("msg_too_close2", Translatable.of("homeblock")));

			// throws when the town's homeblock would be too far from other towns' homeblocks.
			if (TownySettings.getMaxDistanceBetweenHomeblocks() > 0 &&
				distanceToNextNearestHomeblock > TownySettings.getMaxDistanceBetweenHomeblocks())
				throw new TownyException(Translatable.of("msg_too_far"));
		}
	}

	/*
	 * Nation Promixity Methods
	 */

	public static void testTownProximityToNation(Town town, Nation nation) throws TownyException {
		if (TownySettings.getNationProximityToCapital() <= 0)
			return;

		Town capital = nation.getCapital();
		if (!capital.hasHomeBlock() || !town.hasHomeBlock()) {
			throw new TownyException(Translatable.of("msg_err_homeblock_has_not_been_set"));
		}

		WorldCoord capitalCoord = capital.getHomeBlockOrNull().getWorldCoord();
		WorldCoord townCoord = town.getHomeBlockOrNull().getWorldCoord();

		if (!capitalCoord.getWorldName().equalsIgnoreCase(townCoord.getWorldName())) {
			throw new TownyException(Translatable.of("msg_err_nation_homeblock_in_another_world"));
		}

		if (isTownTooFarFromNation(town, capital, nation.getTowns())) {
			throw new TownyException(Translatable.of("msg_err_town_not_close_enough_to_nation", town.getName()));
		}
	}

	public static List<Town> gatherOutOfRangeTowns(Nation nation) {
		return gatherOutOfRangeTowns(nation, nation.getCapital());
	}

	public static List<Town> gatherOutOfRangeTowns(Nation nation, Town capital) {
		List<Town> removedTowns = new ArrayList<>();
		if (TownySettings.getNationProximityToCapital() <= 0)
			return removedTowns;

		final TownBlock capitalHomeBlock = capital.getHomeBlockOrNull();
		// This is unlikely to happen but if a capital has no homeblock by some error we don't want to remove every town.
		if (capitalHomeBlock == null)
			return removedTowns;

		final WorldCoord capitalCoord = capitalHomeBlock.getWorldCoord();
		List<Town> townsToCheck = nation.getTowns();
		List<Town> localRemovedTowns = townsToCheck;
		// We want to parse over the towns to check until we're no longer getting an above 0 amount of towns being removed.
		while (localRemovedTowns.size() > 0) {
			localRemovedTowns = getListOfOutOfRangeTownsFromList(townsToCheck, capital, capitalCoord);
			for (Town localTown : localRemovedTowns) {
				if (!removedTowns.contains(localTown))
					removedTowns.add(localTown);
			}
			townsToCheck = nation.getTowns().stream().filter(t -> !removedTowns.contains(t)).collect(Collectors.toList());
		}
		return removedTowns;
	}

	private static List<Town> getListOfOutOfRangeTownsFromList(List<Town> towns, Town capital, WorldCoord capitalCoord) {
		List<Town> removedTowns = new ArrayList<>();
		for (Town town : towns) {
			// Town is the capital we're measuring against.
			if (town.equals(capital))
				continue;
			// Check that the town missing is not missing a homeblock, and that the
			// homeblocks are in the same world, and the distance between.
			if (isTownTooFarFromNation(town, capital, towns))
				removedTowns.add(town);
		}
		return removedTowns;
	}

	public static void removeOutOfRangeTowns(Nation nation) {
		if (!nation.hasCapital() || TownySettings.getNationProximityToCapital() <= 0)
			return;

		List<Town> toRemove = gatherOutOfRangeTowns(nation);
		if (toRemove.isEmpty())
			return;

		toRemove.stream().forEach(town -> {
			TownyMessaging.sendPrefixedTownMessage(town, Translatable.of("msg_town_left_nation", nation.getName()));
			TownyMessaging.sendPrefixedNationMessage(nation, Translatable.of("msg_nation_town_left", town.getName()));
			town.removeNation();
			town.save();
		});
	}

	public static boolean isTownTooFarFromNation(Town town, Town newCapital, List<Town> towns) {
		if (closeEnoughToCapital(town, newCapital) || closeEnoughToOtherNationTowns(town, newCapital, towns))
			return false;
		return true;
	}

	private static boolean closeEnoughToCapital(Town town, Town newCapital) {
		return closeEnoughToTown(town, newCapital, TownySettings.getNationProximityToCapital());
	}

	private static boolean closeEnoughToOtherNationTowns(Town town, Town newCapital, List<Town> towns) {
		double maxDistanceFromOtherTowns = TownySettings.getNationProximityToOtherNationTowns();
		double maxDistanceFromTheCapital = TownySettings.getNationProximityAbsoluteMaximum();

		// Other towns in the nation are not giving any proximity buff, only the capital is counted.
		if (maxDistanceFromOtherTowns <= 0)
			return false;

		// The town is too far from the nation's absolute cap on proximity from the capital homeblock.
		if (maxDistanceFromTheCapital > 0 && !closeEnoughToTown(town, newCapital, maxDistanceFromTheCapital))
			return false;

		// Try to find at least one town in the nation which is close enough to this town.
		return towns.stream()
				.filter(t -> !t.equals(town) && !t.isCapital())
				.anyMatch(t -> closeEnoughToTown(town, t, maxDistanceFromOtherTowns));
	}

	private static boolean closeEnoughToTown(Town town1, Town town2, double maxAllowedDistance) {
		if (!town1.hasHomeBlock() || !town2.hasHomeBlock())
			return false;

		WorldCoord town1Coord = town1.getHomeBlockOrNull().getWorldCoord();
		WorldCoord town2Coord = town2.getHomeBlockOrNull().getWorldCoord();
		if (!town1Coord.getWorldName().equals(town2Coord.getWorldName()))
			return false;
		return MathUtil.distance(town1Coord, town2Coord) <= maxAllowedDistance;
	}
}
