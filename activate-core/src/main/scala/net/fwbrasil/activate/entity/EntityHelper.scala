package net.fwbrasil.activate.entity

import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable.{ Map => MutableMap }
import net.fwbrasil.activate.util.Reflection.toRichClass
import net.fwbrasil.activate.util.uuid.UUIDUtil
import net.fwbrasil.activate.migration.StorageVersion

object EntityHelper {

    private[this] val entitiesMetadatas =
        MutableMap[String, EntityMetadata]()

    private[this] val concreteEntityClasses =
        MutableMap[Class[_ <: BaseEntity], List[Class[BaseEntity]]]()

    def clearMetadatas = {
        entitiesMetadatas.clear
        concreteEntityClasses.clear
    }

    def metadatas =
        entitiesMetadatas.values.toList.sortBy(_.name)

    def allConcreteEntityClasses = {
        entitiesMetadatas.foreach(m => concreteClasses(m._2.entityClass))
        concreteEntityClasses.values.flatten.toSet
    }

    def concreteClasses[E <: BaseEntity](clazz: Class[E]) =
        concreteEntityClasses.getOrElseUpdate(clazz,
            entitiesMetadatas.map(_._2.entityClass)
                .filter(_.isConcreteClass)
                .filter(clazz.isAssignableFrom)
                .filter(_ != classOf[StorageVersion] || clazz == classOf[StorageVersion])
                .toList.asInstanceOf[List[Class[BaseEntity]]]
                .sortWith((a, b) => !a.isAssignableFrom(b)))

    def initialize(classpathHints: List[Any]): Unit =
        synchronized {
            UUIDUtil.generateUUID
            EntityValue.registerEncodersFor(classpathHints)
            for (entityClass <- EntityEnhancer.enhancedEntityClasses(classpathHints))
                if (!entityClass.isInterface()) {
                    val entityClassHashId = getEntityClassHashId(entityClass)
                    val entityName = getEntityName(entityClass)
                    entitiesMetadatas += (entityClassHashId -> new EntityMetadata(entityName, entityClass))
                }
        }

    def getEntityMetadataOption(clazz: Class[_]) =
        entitiesMetadatas.get(getEntityClassHashId(clazz))

    def getEntityMetadata(clazz: Class[_]) =
        getEntityMetadataOption(clazz).getOrElse(
            throw new IllegalArgumentException(s"Invalid entity class $clazz."))

    // Example ID (45 chars)
    // e1a59a08-7c5b-11e1-91c3-73362e1b7d0d-9a70c810
    // |---------------UUID---------------| |-hash-|
    // 0                                 35 37    44

    private def classHashIdsCache = new ConcurrentHashMap[Class[_], String]()

    def getEntityClassHashId(entityClass: Class[_]): String = {
        var hash = classHashIdsCache.get(entityClass)
        if (hash == null) {
            hash = getEntityClassHashId(getEntityName(entityClass))
            classHashIdsCache.put(entityClass, hash)
        }
        hash
    }

    def getEntityClassFromId(entityId: String) =
        getEntityClassFromIdOption(entityId)
            .getOrElse(throw new IllegalArgumentException(
                s"Invalid entity id: $entityId. Activate uses special UUID with the entity type information, \n" +
                    "so all entities IDs must be generated by Activate. It is possible to generate IDs using IdVar.generateId(entityClass: Class[_]).\n" +
                    "It is also possible to create a domain-specific id, like a column 'userId' and index it, acting as a 'secondary' id."))

    def getEntityClassFromIdOption(entityId: String) =
        if (entityId.length == 45) {
            val hash = entityId.substring(37, 45)
            entitiesMetadatas.get(hash).map(_.entityClass)
        } else
            None

    private def getEntityClassHashId(entityName: String): String =
        normalizeHex(Integer.toHexString(entityName.hashCode))

    private def normalizeHex(hex: String) = {
        val length = hex.length
        if (length == 8)
            hex
        else if (length < 8)
            hex + (for (i <- 0 until (8 - length)) yield "0").mkString("")
        else
            hex.substring(0, 8)
    }

    def getEntityName(entityClass: Class[_]) = {
        val alias = entityClass.getAnnotation(classOf[InternalAlias])
        if (alias != null)
            alias.value
        else {
            entityClass.getSimpleName
        }
    }

}
