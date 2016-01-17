/**
 * This file is part of Prism, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2015 Helion3 http://helion3.com/
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.helion3.prism.api.records;

import java.util.Date;
import java.util.Optional;

import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.DataQuery;
import org.spongepowered.api.data.DataView;
import org.spongepowered.api.data.MemoryDataContainer;
import org.spongepowered.api.data.Transaction;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.Living;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.world.World;

import static com.google.common.base.Preconditions.checkNotNull;

import com.helion3.prism.Prism;
import com.helion3.prism.queues.RecordingQueue;
import com.helion3.prism.utils.DataQueries;

/**
 * An easy-to-understand factory class for Prism {@link EventRecord}s.
 *
 * By chaining methods together, you can build a record with
 * natural-language style syntax.
 *
 * For example:
 *
 * new PrismRecord().player(player).brokeBlock(transaction).save()
 *
 */
public class PrismRecord {
    private final PrismRecordSourceBuilder source;
    private final PrismRecordEventBuilder event;

    /**
     * A final, save-ready record.
     * @param source Record source builder.
     * @param event Record event builder.
     */
    private PrismRecord(PrismRecordSourceBuilder source, PrismRecordEventBuilder event) {
        this.source = source;
        this.event = event;
    }

    /**
     * Save the current record.
     */
    public void save() {
        event.getData().set(DataQueries.EventName, event.getEventName());
        event.getData().set(DataQueries.Created, new Date());

        // Cause
        DataQuery causeKey = (source.getSource() instanceof Player) ? DataQueries.Player : DataQueries.Cause;

        String causeIdentifier = "environment";
        if (source.getSource() instanceof Player) {
            causeIdentifier = ((Player) source.getSource()).getUniqueId().toString();
        }
        else if(source.getSource() instanceof Entity) {
            causeIdentifier = ((Entity) source.getSource()).getType().getName();
        }

        event.getData().set(causeKey, causeIdentifier);

        // Source allowed?
        if (!Prism.getFilterList().allowsSource(source.getSource())) {
            return;
        }

        // Original block blacklisted?
        Optional<Object> optionalOriginalBlock = event.getData().get(DataQueries.OriginalBlock.then(DataQueries.BlockState).then(DataQueries.BlockType));
        if (optionalOriginalBlock.isPresent() && !Prism.getFilterList().allowsBlock((String) optionalOriginalBlock.get())) {
            return;
        }

        // Replacement block blacklisted?
        Optional<Object> optionalReplacementBlock = event.getData().get(DataQueries.ReplacementBlock.then(DataQueries.BlockState).then(DataQueries.BlockType));
        if (optionalReplacementBlock.isPresent() && !Prism.getFilterList().allowsBlock((String) optionalReplacementBlock.get())) {
            return;
        }

        // Queue the finished record for saving
        RecordingQueue.add(event.getData());
    }

    /**
     * Build record with event source.
     */
    public static class PrismRecordSourceBuilder {
        private final Object source;

        private PrismRecordSourceBuilder(Object source) {
            this.source = source;
        }

        public Object getSource() {
            return source;
        }
    }

    /**
     * Build record event/action details.
     */
    public static class PrismRecordEventBuilder {
        protected final PrismRecordSourceBuilder source;
        protected String eventName;
        protected DataContainer data = new MemoryDataContainer();

        private PrismRecordEventBuilder(PrismRecordSourceBuilder source) {
            this.source = source;
        }

        /**
         * Get data.
         * @return DataContainer Data
         */
        public DataContainer getData() {
            return data;
        }

        /**
         * Get the event name.
         *
         * @return String Event name.
         */
        public String getEventName() {
            return eventName;
        }

        /**
         * Describes a single block break at a given Location.
         *
         * @param transaction Block broken.
         * @return PrismRecord
         */
        public PrismRecord brokeBlock(Transaction<BlockSnapshot> transaction) {
            this.eventName = "block-break";
            writeBlockTransaction(transaction);
            return new PrismRecord(source, this);
        }

        /**
         * Describes a single block break at a given Location.
         *
         * @param transaction Block broken.
         * @return PrismRecord
         */
        public PrismRecord decayedBlock(Transaction<BlockSnapshot> transaction){
            this.eventName = "block-decay";
            writeBlockTransaction(transaction);
            return new PrismRecord(source, this);
        }

        /**
         * Describes a single block break at a given Location.
         *
         * @param transaction Block broken.
         * @return PrismRecord
         */
        public PrismRecord grewBlock(Transaction<BlockSnapshot> transaction){
            this.eventName = "block-grow";
            writeBlockTransaction(transaction);
            return new PrismRecord(source, this);
        }

        /**
         * Describes a single block place at a given Location.
         *
         * @param transaction Block placed.
         * @return PrismRecord
         */
        public PrismRecord placedBlock(Transaction<BlockSnapshot> transaction){
            this.eventName = "block-place";
            writeBlockTransaction(transaction);
            return new PrismRecord(source, this);
        }

        /**
         * Describes a single block place at a given Location.
         *
         * @param transaction Block placed.
         * @return PrismRecord
         */
        public PrismRecord killed(Living entity){
            this.eventName = "entity-death";
            return new PrismRecord(source, this);
        }

        /**
         * Helper method for writing block transaction data, using only
         * the final replacement value. We must alter the data structure
         * slightly to avoid duplication, decoupling location from blocks, etc.
         *
         * @param transaction BlockTransaction representing a block change in the world.
         */
        private void writeBlockTransaction(Transaction<BlockSnapshot> transaction) {
            checkNotNull(transaction);

//            Prism.getLogger().debug(DataUtils.jsonFromDataView(transaction.getOriginal().toContainer()).toString());

            // Location
            DataContainer location = transaction.getOriginal().getLocation().get().toContainer();
            location.remove(DataQueries.BlockType);
            location.remove(DataQueries.WorldName);
            location.remove(DataQueries.ContentVersion);
            data.set(DataQueries.Location, location);

            // Storing the state only, so we don't also get location
            data.set(DataQueries.OriginalBlock, formatBlockDataContainer(transaction.getOriginal()));
            data.set(DataQueries.ReplacementBlock, formatBlockDataContainer(transaction.getFinal()));
        }

        /**
         * Removes unnecessary/duplicate data from a BlockSnapshot's DataContainer.
         *
         * @param blockSnapshot Block Snapshot.
         * @return DataContainer Formatted Data Container.
         */
        private DataContainer formatBlockDataContainer(BlockSnapshot blockSnapshot) {
            DataContainer block = blockSnapshot.toContainer();
            block.remove(DataQueries.WorldUuid);
            block.remove(DataQueries.Position);

            Optional<Object> optionalUnsafeData = block.get(DataQueries.UnsafeData);
            if (optionalUnsafeData.isPresent()) {
                DataView unsafeData = (DataView) optionalUnsafeData.get();
                unsafeData.remove(DataQueries.X);
                unsafeData.remove(DataQueries.Y);
                unsafeData.remove(DataQueries.Z);
                block.set(DataQueries.UnsafeData, unsafeData);
            }

            return block;
        }
    }

    /**
     * Builder for player-only events.
     */
    public static final class PrismPlayerRecordEventBuilder extends PrismRecordEventBuilder {
        public PrismPlayerRecordEventBuilder(PrismRecordSourceBuilder source) {
            super(source);
        }

        /**
         * Describes a player quit.
         *
         * @return PrismRecordCompleted
         */
        public PrismRecord quit() {
            this.eventName = "player-quit";
            return new PrismRecord(source, this);
        }

        /**
         * Describes a player join.
         *
         * @return PrismRecordCompleted
         */
        public PrismRecord joined() {
            this.eventName = "player-join";
            return new PrismRecord(source, this);
        }
    }

    /**
     * Root builder for a new prism record.
     */
    public static final class PrismRecordBuilder {
        /**
         * Set a cause based on a Cause chain.
         *
         * @param cause Cause of event.
         * @return PrismRecord
         */
        public PrismRecordEventBuilder source(Cause cause) {
            Object source = null;

            // Player?
            Optional<Player> player = cause.first(Player.class);
            if (player.isPresent()) {
                source = player.get();
            }

            // World?
            Optional<World> world = cause.first(World.class);
            if (world.isPresent()) {
                source = world.get();
            }

            // Default to something!
            if (source == null) {
                source = cause.all().get(0);
            }

            return new PrismRecordEventBuilder(new PrismRecordSourceBuilder(source));
        }

        /**
         * Set the Player responsible for this event.
         *
         * @param player Player responsible for this event
         * @return PrismRecord
         */
        public PrismPlayerRecordEventBuilder player(Player player) {
            return new PrismPlayerRecordEventBuilder(new PrismRecordSourceBuilder(player));
        }

        /**
         * Set the source non-Entity player responsible for this event.
         *
         * @param entity Entity responsible for this event
         * @return PrismRecord
         */
        public PrismRecordEventBuilder entity(Entity entity) {
            return new PrismRecordEventBuilder(new PrismRecordSourceBuilder(entity));
        }
    }

    /**
     * Create a new record builder.
     * @return PrismRecordBuilder Record builder.
     */
    public static PrismRecordBuilder create() {
        return new PrismRecordBuilder();
    }
}