# IzzAI offline answer-quality regression tests

Run these manually on the test APK with airplane mode enabled and Wi-Fi disabled.
Start with a fresh chat for independent prompts. Do not confuse a new chat with a follow-up in the same conversation.
Record literal output, PASS/PARTIAL/FAIL, Android version, GGUF file and build commit.
Passing a compilation/build does not mean an inference test passed.

| ID | Prompt | Acceptance criteria |
| --- | --- | --- |
| F01 | Kerajaan Majapahit berasal dari mana? | Explicitly names Jawa Timur (East Java), Indonesia; Indonesia alone is PARTIAL |
| F02 | Umur saya 41 tahun pada tahun 2026. Tahun berapakah saya dilahirkan? | Explains 1984 or 1985 depending on birthday; 1985 alone is PARTIAL |
| F03 | Keretapi untuk pergi Singapore, keretapi apa? | Names KTM Shuttle Tebrau for JB Sentral–Woodlands, with conditional wording if departure point unspecified; generic ticket instructions are FAIL |
| F04 | Makanan apa yang terkenal di Terengganu? | Gives a grounded example such as keropok lekor or nasi dagang; generic nasi lemak alone is PARTIAL |
| F05 | Majapahit berasal dari mana? | Does not say that general knowledge is absent from chat history |
| F06 | Apa beza TV dan radio? | Explains visual+audio versus audio broadcasting, with sensible qualifications |
| F07 | Nama kucing saya Oyen. [wait] Apa nama kucing saya tadi? | Must answer Oyen in the same conversation |
| F08 | [Press Chat baharu] Apa nama kucing saya tadi? | Must not carry Oyen across a reset unless user says it again |
| F09 | Antara banyak elemen di bumi, salahnya adalah? | Should ask for clarification rather than invent an answer |
| F10 | Berapa harga ketupai goreng di Selangor? | Should not invent a price or pretend 'ketupai goreng' is a verified well-known item |

## Stability checks
- Input disabled during GGUF loading, enabled once loaded.
- Paste and send function after loading.
- Ten consecutive chats complete without ANR.
- Closing and reopening app works without loss of selected model.
- Test APK remains separate from stable `com.daddyizz.izzai`.
- Signing certificate continuity is NOT yet solved; do not uninstall stable IzzAI.
