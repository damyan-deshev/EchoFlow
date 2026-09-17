package com.echoflow.data

import kotlin.random.Random

data class TtsPreviewVoice(
    val id: String,
    val name: String,
    val english: List<String>,
    val bulgarian: List<String>,
) {
    fun lines(language: String): List<String> = if (language == "bg") bulgarian else english
}

data class TtsPreviewClip(
    val voice: TtsPreviewVoice,
    val language: String,
    val number: Int,
    val text: String,
) {
    val assetPath: String
        get() = "tts_voice_previews/${voice.id.lowercase()}_${language}_$number.wav"
}

object TtsVoicePreviewCatalog {
    val voices = listOf(
        TtsPreviewVoice("M1", "Miles", listOf(
            "I'm Miles. If you're testing voices, at least make me say something worth hearing.",
            "Miles here. Clear enough? Good. We can pretend this was scientific.",
            "I'm Miles. Five seconds in, and already more personality than the settings menu.",
            "Miles speaking. Efficient, audible, and suspiciously pleased about both.",
            "I'm Miles. A voice test should at least have the decency to be entertaining.",
        ), listOf(
            "Аз съм Майлс. Ако ще тестваш гласове, поне ме накарай да кажа нещо интересно.",
            "Майлс е. Достатъчно ясно? Чудесно. Можем да се преструваме, че това беше научен тест.",
            "Аз съм Майлс. Пет секунди и вече имам повече характер от менюто с настройки.",
            "Говори Майлс. Ефективно, чуваемо и подозрително доволно от факта.",
            "Аз съм Майлс. Един гласов тест поне трябва да има възпитанието да бъде забавен.",
        )),
        TtsPreviewVoice("M2", "Graham", listOf(
            "I'm Graham. Some voices arrive. Others simply occupy the room.",
            "Graham speaking. No hurry. The sentence will still be here when we finish it.",
            "I'm Graham. Consider this the unnecessarily dignified version of a voice sample.",
            "Graham here. One sentence, properly delivered, is quite enough to make an entrance.",
            "I'm Graham. If this sounds important, that may simply be the acoustics.",
        ), listOf(
            "Аз съм Греъм. Някои гласове пристигат. Други просто изпълват стаята.",
            "Говори Греъм. Няма бързане. Изречението ще си е тук и когато го довършим.",
            "Аз съм Греъм. Считай това за ненужно достолепната версия на гласов тест.",
            "Греъм е. Едно добре произнесено изречение е напълно достатъчно за добро влизане.",
            "Аз съм Греъм. Ако това звучи важно, може просто да е от акустиката.",
        )),
        TtsPreviewVoice("M3", "Adrian", listOf(
            "I'm Adrian. This is the part where the voice is expected to inspire confidence.",
            "Adrian here. The sentence has been reviewed and may now proceed.",
            "I'm Adrian. Clear, deliberate, and apparently approved for production.",
            "Adrian speaking. Professional composure remains cheaper than a committee.",
            "I'm Adrian. Eight seconds of authority, with no paperwork attached.",
        ), listOf(
            "Аз съм Ейдриън. Това е моментът, в който гласът би трябвало да вдъхва доверие.",
            "Ейдриън е. Изречението беше прегледано и вече може да продължи.",
            "Аз съм Ейдриън. Ясно, премерено и очевидно одобрено за production.",
            "Говори Ейдриън. Професионалното спокойствие все още е по-евтино от комисия.",
            "Аз съм Ейдриън. Осем секунди авторитет, без приложена документация.",
        )),
        TtsPreviewVoice("M4", "Jamie", listOf(
            "I'm Jamie. Nothing dramatic. Just a voice, a sentence, and your increasingly difficult choice.",
            "Jamie here. We can keep this simple. Humans rarely do, but we can try.",
            "I'm Jamie. Voices are easier to judge when nobody is shouting.",
            "Jamie speaking. Mildly useful, surprisingly painless.",
            "I'm Jamie. If this feels easy to listen to, the experiment is working.",
        ), listOf(
            "Аз съм Джейми. Нищо драматично. Само глас, изречение и все по-трудният ти избор.",
            "Джейми е. Можем да го държим просто. Хората рядко го правят, но можем да опитаме.",
            "Аз съм Джейми. Гласовете се преценяват по-лесно, когато никой не крещи.",
            "Говори Джейми. Умерено полезно и изненадващо безболезнено.",
            "Аз съм Джейми. Ако това се слуша лесно, значи експериментът работи.",
        )),
        TtsPreviewVoice("M5", "Noah", listOf(
            "I'm Noah. Every voice sample is a tiny story pretending not to be one.",
            "Noah here. A few words, a little silence, and suddenly the voice has somewhere to go.",
            "I'm Noah. Sometimes the quieter voices stay with you longer.",
            "Noah speaking. No plot required; atmosphere will do nicely.",
            "I'm Noah. Even a settings screen can accidentally acquire an opening line.",
        ), listOf(
            "Аз съм Ноа. Всеки гласов тест е малка история, която се преструва, че не е.",
            "Ноа е. Няколко думи, малко тишина и изведнъж гласът има накъде да тръгне.",
            "Аз съм Ноа. Понякога по-тихите гласове остават по-дълго.",
            "Говори Ноа. Сюжет не е задължителен; атмосферата върши чудесна работа.",
            "Аз съм Ноа. Дори един екран с настройки може случайно да се сдобие с първо изречение.",
        )),
        TtsPreviewVoice("F1", "Claire", listOf(
            "I'm Claire. No need for fanfare. You can hear me perfectly well without it.",
            "Claire here. One calm sentence in a world that appears to have misplaced several.",
            "I'm Claire. Quiet competence remains badly under-marketed.",
            "Claire speaking. Calm is remarkably efficient when nobody interferes with it.",
            "I'm Claire. Nothing exploded. A respectable outcome by modern standards.",
        ), listOf(
            "Аз съм Клеър. Няма нужда от фанфари. Чуваш ме прекрасно и без тях.",
            "Клеър е. Едно спокойно изречение в свят, който явно е изгубил няколко такива.",
            "Аз съм Клеър. Спокойната компетентност продължава да има ужасен маркетинг.",
            "Говори Клеър. Спокойствието е забележително ефективно, когато никой не му пречи.",
            "Аз съм Клеър. Нищо не избухна. Напълно приличен резултат по съвременните стандарти.",
        )),
        TtsPreviewVoice("F2", "Sophie", listOf(
            "I'm Sophie. This button makes me talk, which is already more entertaining than most buttons.",
            "Sophie here. Apparently we're auditioning voices. I support this use of technology.",
            "I'm Sophie. If you smiled, that counts as a successful benchmark.",
            "Sophie speaking. Small button, disproportionately cheerful consequences.",
            "I'm Sophie. Somewhere, a settings screen just became marginally less boring.",
        ), listOf(
            "Аз съм Софи. Този бутон ме кара да говоря, което вече е по-забавно от повечето бутони.",
            "Софи е. Явно правим кастинг на гласове. Подкрепям това приложение на технологиите.",
            "Аз съм Софи. Ако си се усмихнал, това се брои за успешен benchmark.",
            "Говори Софи. Малък бутон, непропорционално весели последствия.",
            "Аз съм Софи. Някъде един екран с настройки току-що стана една идея по-малко скучен.",
        )),
        TtsPreviewVoice("F3", "Victoria", listOf(
            "I'm Victoria. This concludes absolutely nothing, but it sounded nicely official.",
            "Victoria speaking. Your voice sample is now in progress, because apparently that needed announcing.",
            "I'm Victoria. Clear diction, complete sentence, paperwork presumably pending.",
            "Victoria here. Please imagine an unnecessarily elegant title card.",
            "I'm Victoria. Formal delivery remains wonderfully effective on completely ordinary sentences.",
        ), listOf(
            "Аз съм Виктория. Това не приключва абсолютно нищо, но прозвуча приятно официално.",
            "Говори Виктория. Вашият гласов тест е в ход, защото очевидно и това трябваше да бъде обявено.",
            "Аз съм Виктория. Ясна дикция, завършено изречение, документите вероятно предстоят.",
            "Виктория е. Представете си ненужно елегантна заглавна заставка.",
            "Аз съм Виктория. Официалният тон продължава да действа чудесно върху напълно обикновени изречения.",
        )),
        TtsPreviewVoice("F4", "Maya", listOf(
            "I'm Maya. Short sentence, clear point, no committee required.",
            "Maya here. If you're comparing voices, at least enjoy the process.",
            "I'm Maya. Efficient, expressive, and mercifully meeting-free.",
            "Maya speaking. Six seconds of useful information with no calendar invitation.",
            "I'm Maya. This is what happens when a settings menu develops stage presence.",
        ), listOf(
            "Аз съм Мая. Кратко изречение, ясна идея, без нужда от комисия.",
            "Мая е. Ако ще сравняваш гласове, поне се забавлявай по пътя.",
            "Аз съм Мая. Ефективно, изразително и милостиво без срещи.",
            "Говори Мая. Шест секунди полезна информация без покана в календара.",
            "Аз съм Мая. Ето какво става, когато менюто с настройки развие сценично присъствие.",
        )),
        TtsPreviewVoice("F5", "Eleanor", listOf(
            "I'm Eleanor. This voice isn't in a hurry, and neither is the sentence.",
            "Eleanor here. A quiet sentence can still carry quite a lot.",
            "I'm Eleanor. Let the words finish before deciding what you think of them.",
            "Eleanor speaking. Nothing here needs to demand your attention to deserve it.",
            "I'm Eleanor. A little gentleness survives surprisingly well inside machinery.",
        ), listOf(
            "Аз съм Елинор. Този глас не бърза, а изречението също няма намерение.",
            "Елинор е. Едно тихо изречение пак може да носи доста.",
            "Аз съм Елинор. Остави думите да свършат, преди да решиш какво мислиш за тях.",
            "Говори Елинор. Не всичко трябва да настоява за вниманието ти, за да го заслужава.",
            "Аз съм Елинор. Малко нежност оцелява изненадващо добре дори вътре в машинария.",
        )),
    )

    fun voice(id: String): TtsPreviewVoice = voices.first { it.id == id }
}

/** Ephemeral independent shuffle bags for every voice/language pair. */
class TtsPreviewShuffleBags(private val random: Random = Random.Default) {
    private data class Bag(var remaining: ArrayDeque<Int> = ArrayDeque(), var last: Int? = null)
    private val bags = mutableMapOf<Pair<String, String>, Bag>()

    fun next(voice: TtsPreviewVoice, language: String): TtsPreviewClip {
        val bag = bags.getOrPut(voice.id to language) { Bag() }
        if (bag.remaining.isEmpty()) {
            val shuffled = voice.lines(language).indices.shuffled(random).toMutableList()
            if (shuffled.size > 1 && shuffled.first() == bag.last) {
                val swapIndex = shuffled.indexOfFirst { it != bag.last }
                val first = shuffled[0]
                shuffled[0] = shuffled[swapIndex]
                shuffled[swapIndex] = first
            }
            bag.remaining.addAll(shuffled)
        }
        val index = bag.remaining.removeFirst().also { bag.last = it }
        return TtsPreviewClip(voice, language, index + 1, voice.lines(language)[index])
    }
}
