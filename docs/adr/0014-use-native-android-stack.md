# Use native Android stack

The app will use Kotlin, Jetpack Compose, and Room as its Android stack instead of Flutter or React Native. The product's hardest work is Android system integration, including notification listening, accessibility services, local sensitive-data storage, and domestic ROM behavior, so keeping the core app native reduces bridge complexity and makes permission-sensitive behavior easier to implement and review.
