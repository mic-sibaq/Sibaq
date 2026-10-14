import qrcode

data = "https://mic-sibaq.vercel.app/student.html?id=901"
img = qrcode.make(data)

img.save("qrcode.png")