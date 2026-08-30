package com.baba.callvault.stt

/**

 * Personnes dont la voix peut être reconnue.
 *
 * Pour l'instant, nous avons uniquement :
 *
 * * Marcelle
 * * Tony
 * * Sonia
 * * Bruno
 */
enum class VoicePerson(
    val displayName: String
) {

    MARCELLE(
        "Marcelle"
    ),

    TONY(
        "Tony"
    ),

    SONIA(
        "Sonia"
    ),

    BRUNO(
        "Bruno"
    )
}

/**

 * Configuration des voix attendues selon le contact appelé.
 */
object VoiceProfiles {

    /**

     * Retourne les personnes pouvant être présentes
     * pour un contact donné.
     */
    fun personsForContact(
        contactName: String
    ): List<VoicePerson> {

        return when (
            contactName.trim().lowercase()
        ) {


                "marcelle" -> listOf(
                VoicePerson.MARCELLE,
                VoicePerson.TONY
            )

            "tony" -> listOf(
                VoicePerson.TONY,
                VoicePerson.MARCELLE
            )

            "sonia" -> listOf(
                VoicePerson.SONIA,
                VoicePerson.BRUNO
            )

            "bruno" -> listOf(
                VoicePerson.BRUNO,
                VoicePerson.SONIA
            )

            else -> emptyList()


        }
    }
}
