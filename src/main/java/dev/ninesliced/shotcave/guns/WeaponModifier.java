package dev.ninesliced.shotcave.guns;

import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;

import javax.annotation.Nonnull;

public record WeaponModifier(@Nonnull WeaponModifierType type, double rolledValue) {

    @Nonnull
    public BsonDocument toBsonDocument() {
        BsonDocument doc = new BsonDocument();
        doc.put("t", new BsonInt32(type.ordinal()));
        doc.put("v", new BsonDouble(rolledValue));
        return doc;
    }

    @Nonnull
    public static WeaponModifier fromBsonDocument(@Nonnull BsonDocument doc) {
        int typeOrdinal = doc.containsKey("t") ? doc.getInt32("t").getValue() : 0;
        double value = doc.containsKey("v") ? doc.getDouble("v").getValue() : 0.0;
        return new WeaponModifier(WeaponModifierType.fromOrdinal(typeOrdinal), value);
    }
}
