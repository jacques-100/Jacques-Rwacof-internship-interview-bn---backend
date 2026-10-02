package com.rwacof.cherrytrack.repository;

import com.rwacof.cherrytrack.model.StoredImage;
import com.rwacof.cherrytrack.model.StoredImage.OwnerType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface StoredImageRepository extends JpaRepository<StoredImage, Long> {

    Optional<StoredImage> findByOwnerTypeAndOwnerId(OwnerType ownerType, Long ownerId);

    /** Just the timestamp, so "has a logo?" never loads the bytes. */
    @Query("select i.updatedAt from StoredImage i where i.ownerType = :type and i.ownerId = :id")
    Optional<Instant> findUpdatedAt(OwnerType type, Long id);

    @Modifying
    @Query("delete from StoredImage i where i.ownerType = :type and i.ownerId = :id")
    int deleteByOwner(OwnerType type, Long id);
}
