import qrcode 

Data = "https://mic-sibaq.vercel.app/student.html?id=1016"
img = qrcode.make(Data)

img.save("Shayan.png")

