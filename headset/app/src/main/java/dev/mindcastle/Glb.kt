package dev.mindcastle

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal binary glTF (.glb) writer: one triangle mesh, one unlit (KHR_materials_unlit), double-sided
 * material of a flat [rgba] color. Positions are meters, indices 32-bit.
 */
object Glb {
    fun write(positions: FloatArray, indices: IntArray, rgba: FloatArray): ByteArray {
        val n = positions.size / 3
        val posBytes = positions.size * 4; val idxBytes = indices.size * 4
        val bin = ByteBuffer.allocate(pad4(posBytes + idxBytes)).order(ByteOrder.LITTLE_ENDIAN)
        positions.forEach { bin.putFloat(it) }
        indices.forEach { bin.putInt(it) }
        val min = JSONArray(); val max = JSONArray()
        for (a in 0 until 3) {
            min.put((a until positions.size step 3).minOf { positions[it] }.toDouble())
            max.put((a until positions.size step 3).maxOf { positions[it] }.toDouble())
        }
        val json = JSONObject()
            .put("asset", JSONObject().put("version", "2.0"))
            .put("extensionsUsed", JSONArray().put("KHR_materials_unlit"))
            .put("scene", 0)
            .put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
            .put("nodes", JSONArray().put(JSONObject().put("mesh", 0)))
            .put("meshes", JSONArray().put(JSONObject().put("primitives", JSONArray().put(
                JSONObject().put("attributes", JSONObject().put("POSITION", 0)).put("indices", 1).put("material", 0).put("mode", 4)))))
            .put("materials", JSONArray().put(JSONObject()
                .put("pbrMetallicRoughness", JSONObject()
                    .put("baseColorFactor", JSONArray().apply { rgba.forEach { put(it.toDouble()) } })
                    .put("metallicFactor", 0).put("roughnessFactor", 1))
                .put("extensions", JSONObject().put("KHR_materials_unlit", JSONObject()))
                .put("alphaMode", if (rgba[3] < 1f) "BLEND" else "OPAQUE")
                .put("doubleSided", true)))
            .put("buffers", JSONArray().put(JSONObject().put("byteLength", bin.capacity())))
            .put("bufferViews", JSONArray()
                .put(JSONObject().put("buffer", 0).put("byteOffset", 0).put("byteLength", posBytes).put("target", 34962))
                .put(JSONObject().put("buffer", 0).put("byteOffset", posBytes).put("byteLength", idxBytes).put("target", 34963)))
            .put("accessors", JSONArray()
                .put(JSONObject().put("bufferView", 0).put("componentType", 5126).put("count", n).put("type", "VEC3").put("min", min).put("max", max))
                .put(JSONObject().put("bufferView", 1).put("componentType", 5125).put("count", indices.size).put("type", "SCALAR")))
        val js = json.toString().toByteArray(Charsets.UTF_8)
        val jsLen = pad4(js.size)
        val total = 12 + 8 + jsLen + 8 + bin.capacity()
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67); putInt(2); putInt(total)
            putInt(jsLen); putInt(0x4E4F534A); put(js); repeat(jsLen - js.size) { put(' '.code.toByte()) }
            putInt(bin.capacity()); putInt(0x004E4942); put(bin.array())
        }.array()
    }

    private fun pad4(n: Int) = (n + 3) and 3.inv()
}
