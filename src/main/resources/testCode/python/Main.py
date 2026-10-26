import sys

def main():
    data = sys.stdin.read().strip().split()
    if not data:
        return
    a, b = int(data[0]), int(data[1])
    print(a + b)

if __name__ == "__main__":
    main()
