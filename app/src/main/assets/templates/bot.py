"""Set TELEGRAM_BOT_TOKEN as a secret in Project settings, then run this bot."""
import asyncio
import os

from telegram import Update
from telegram.ext import Application, CommandHandler, ContextTypes


async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_message:
        await update.effective_message.reply_text("Hello from runcode on Android!")


if __name__ == "__main__":
    token = os.environ.get("TELEGRAM_BOT_TOKEN", "")
    if not token or token.startswith("${SEC_"):
        raise SystemExit("Set TELEGRAM_BOT_TOKEN in Project settings before running.")
    # Services run on worker threads, which do not have a default asyncio loop or signals.
    asyncio.set_event_loop(asyncio.new_event_loop())
    application = Application.builder().token(token).build()
    application.add_handler(CommandHandler("start", start))
    print("Connecting to Telegram. Send /start to your bot.")
    application.run_polling(stop_signals=None)
